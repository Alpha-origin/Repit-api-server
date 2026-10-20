package repit.repit_api_server.domain.metadata.service;

import org.junit.jupiter.api.Test;
import repit.repit_api_server.domain.metadata.dto.response.AuthMaterialsResponse;
import repit.repit_api_server.domain.metadata.dto.response.MetaDataResponse;
import repit.repit_api_server.global.client.AuthServerClient;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 옛 자료 조회(getMetaData)의 fileUrl은 포트폴리오 자리다.
 *
 * <p>문서 순서는 사용자가 정하므로 첫 문서가 이력서일 수 있다. 순서로 고르면 /getMetaData가 이력서를 포트폴리오로
 * 내보내고, /isPortfolio는 포트폴리오가 없는데도 true를 준다.
 */
class MetaServiceTest {

    private final AuthServerClient authServerClient = mock(AuthServerClient.class);
    private final MetaService service = new MetaService(mock(S3Client.class), authServerClient);

    private void givenDocuments(AuthMaterialsResponse.DocumentEntry... documents) {
        when(authServerClient.getMaterials("Bearer token")).thenReturn(new AuthMaterialsResponse(
                "MAJOR_BACKEND", List.of(documents), List.of("https://github.com/a/b"), List.of(), null));
    }

    @Test
    void 첫_문서가_이력서여도_포트폴리오_주소를_돌려준다() {
        givenDocuments(
                new AuthMaterialsResponse.DocumentEntry("RESUME", "https://s3/resume.pdf", "이력서.pdf", 2),
                new AuthMaterialsResponse.DocumentEntry("PORTFOLIO", "https://s3/portfolio.pdf", "포트폴리오.pdf", 10));

        MetaDataResponse metaData = service.getMetaData("Bearer token");

        assertThat(metaData.getFileUrl()).isEqualTo("https://s3/portfolio.pdf");
        assertThat(metaData.getGitUrls()).containsExactly("https://github.com/a/b");
    }

    @Test
    void 포트폴리오가_없으면_다른_문서가_있어도_fileUrl은_비어_있다() {
        givenDocuments(
                new AuthMaterialsResponse.DocumentEntry("RESUME", "https://s3/resume.pdf", "이력서.pdf", 2),
                new AuthMaterialsResponse.DocumentEntry("CAREER_DESCRIPTION", "https://s3/career.pdf", "경력.pdf", 3));

        assertThat(service.getMetaData("Bearer token").getFileUrl()).isNull();
    }
}
