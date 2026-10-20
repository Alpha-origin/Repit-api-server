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

    @Test
    void 분석에는_첫_문서가_아니라_앞의_포트폴리오_문서와_GitHub를_싣는다() {
        AuthMaterialsResponse auth = new AuthMaterialsResponse("MAJOR_BACKEND",
                List.of(new DocumentEntry("RESUME", "https://s3/resume.pdf", "이력서.pdf", 2),
                        new DocumentEntry("PORTFOLIO", "https://s3/portfolio.pdf", "포트폴리오.pdf", 9),
                        new DocumentEntry("PORTFOLIO", "https://s3/portfolio-2.pdf", "포트폴리오2.pdf", 4)),
                List.of("https://github.com/a/api"), List.of(), null);

        MetaDataResponse input = MaterialsResponse.from(auth).toAnalysisInput();

        assertThat(input.getFileUrl()).isEqualTo("https://s3/portfolio.pdf");
        assertThat(input.getGitUrls()).containsExactly("https://github.com/a/api");
    }

    @Test
    void 옛_사용자의_PDF도_포트폴리오로_분석에_싣는다() {
        AuthMaterialsResponse auth = new AuthMaterialsResponse(
                null, List.of(), List.of("https://github.com/a/repo"), List.of(), "https://s3/legacy.pdf");

        assertThat(MaterialsResponse.from(auth).toAnalysisInput().getFileUrl()).isEqualTo("https://s3/legacy.pdf");
    }

    @Test
    void 포트폴리오_문서가_없으면_분석에_실을_파일_주소는_null이다() {
        AuthMaterialsResponse auth = new AuthMaterialsResponse("MAJOR_QA_ENGINEER",
                List.of(new DocumentEntry("TEST_PLAN", "https://s3/plan.pdf", "plan.pdf", 3)), List.of(), List.of(), null);

        assertThat(MaterialsResponse.from(auth).toAnalysisInput().getFileUrl()).isNull();
    }
    @Test
    void 일반_직무명과_자소서는_인증_서버_값을_순서대로_옮긴다() {
        AuthMaterialsResponse auth = new AuthMaterialsResponse("MAJOR_GEN_MARKETING", List.of(), List.of(),
                List.of(), null, "브랜드 마케터", List.of(
                new AuthMaterialsResponse.CoverLetterEntry("지원 동기", "브랜드를 키웠습니다"),
                new AuthMaterialsResponse.CoverLetterEntry("성과", "매출 증가")));

        MaterialsResponse response = MaterialsResponse.from(auth);

        assertThat(response.jobRole()).isEqualTo("GEN_MARKETING");
        assertThat(response.jobTitle()).isEqualTo("브랜드 마케터");
        assertThat(response.coverLetters()).containsExactly(
                new MaterialsResponse.CoverLetter("지원 동기", "브랜드를 키웠습니다"),
                new MaterialsResponse.CoverLetter("성과", "매출 증가"));
        assertThat(MaterialsResponse.empty().jobTitle()).isNull();
        assertThat(MaterialsResponse.empty().coverLetters()).isEmpty();
        assertThat(MaterialsResponse.from(new AuthMaterialsResponse("MAJOR_GEN_DESIGN", null, null,
                null, null, "디자이너", null)).coverLetters()).isEmpty();
    }
}
