package repit.repit_api_server.domain.metadata.dto.response;

import org.junit.jupiter.api.Test;
import repit.repit_api_server.domain.metadata.dto.response.AuthMaterialsResponse.DocumentEntry;
import repit.repit_api_server.domain.metadata.dto.response.AuthMaterialsResponse.SupplementEntry;
import repit.repit_api_server.domain.metadata.dto.response.MaterialsResponse.MaterialDocument;
import repit.repit_api_server.domain.metadata.dto.response.MaterialsResponse.Supplement;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 인증 서버의 저장값(C-2)을 마이페이지 응답(C-1)으로 바꾸는 규칙.
 */
class MaterialsResponseTest {

    @Test
    void 새_형식으로_저장한_자료는_접두사를_떼고_문서_순서대로_index를_붙인다() {
        AuthMaterialsResponse auth = new AuthMaterialsResponse(
                "MAJOR_PRODUCT_MANAGER",
                List.of(new DocumentEntry("PRD", "https://s3/prd.pdf", "회원가입-PRD.pdf", 9),
                        new DocumentEntry("POLICY_DOC", "https://s3/policy.pdf", "정책서.pdf", 6)),
                List.of(),
                List.of(new SupplementEntry("회원가입 개편", 0, "이탈 줄이기", "정책 정리", "단계 축소", null)),
                null);

        MaterialsResponse response = MaterialsResponse.from(auth);

        assertThat(response.jobRole()).isEqualTo("PRODUCT_MANAGER");
        assertThat(response.documents()).containsExactly(
                new MaterialDocument(0, "PRD", "https://s3/prd.pdf", "회원가입-PRD.pdf", 9),
                new MaterialDocument(1, "POLICY_DOC", "https://s3/policy.pdf", "정책서.pdf", 6));
        assertThat(response.gitUrls()).isEmpty();
        assertThat(response.supplements()).containsExactly(
                new Supplement("회원가입 개편", 0, "이탈 줄이기", "정책 정리", "단계 축소", null));
    }

    @Test
    void 옛_사용자의_PDF는_파일명과_쪽수가_없는_PORTFOLIO_문서_한_건이다() {
        AuthMaterialsResponse auth = new AuthMaterialsResponse(
                null, List.of(), List.of("https://github.com/a/repo"), List.of(), "https://s3/legacy.pdf");

        MaterialsResponse response = MaterialsResponse.from(auth);

        assertThat(response.jobRole()).isNull();
        assertThat(response.documents()).containsExactly(
                new MaterialDocument(0, "PORTFOLIO", "https://s3/legacy.pdf", null, null));
        assertThat(response.gitUrls()).containsExactly("https://github.com/a/repo");
    }

    @Test
    void 아무것도_저장하지_않았으면_직무는_null이고_목록은_비어_있다() {
        AuthMaterialsResponse auth = new AuthMaterialsResponse(null, null, null, null, null);

        MaterialsResponse response = MaterialsResponse.from(auth);

        assertThat(response).isEqualTo(MaterialsResponse.empty());
        assertThat(response.documents()).isEmpty();
        assertThat(response.gitUrls()).isEmpty();
        assertThat(response.supplements()).isEmpty();
    }

    @Test
    void 빈_문자열_PDF_주소는_문서로_보지_않는다() {
        AuthMaterialsResponse auth = new AuthMaterialsResponse("MAJOR_BACKEND", List.of(), List.of(), List.of(), " ");

        assertThat(MaterialsResponse.from(auth).documents()).isEmpty();
    }

    @Test
    void 인증_서버가_빈_응답을_주면_빈_자료다() {
        assertThat(MaterialsResponse.from(null)).isEqualTo(MaterialsResponse.empty());
    }
}
