package repit.repit_api_server.domain.metadata.support;

import org.junit.jupiter.api.Test;
import repit.repit_api_server.domain.metadata.support.JobRole.GithubRule;
import repit.repit_api_server.domain.metadata.support.JobRole.JobFamily;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** 비개발 직군 명세 01 §3.1 표와 같은지 확인한다. 서버마다 같은 표를 상수로 둔다(가정 A-1). */
class JobRoleTest {

    @Test
    void 직무_코드는_명세의_15개다() {
        assertThat(Arrays.stream(JobRole.values()).map(Enum::name)).containsExactly(
                "FRONTEND", "BACKEND", "WEB_PUBLISHER",
                "DATA_ANALYST", "DBA_INFRA", "QA_ENGINEER", "SECURITY", "PRODUCT_MANAGER", "IT_CONSULTANT",
                "GEN_STRATEGY", "GEN_HR_ADMIN", "GEN_FINANCE", "GEN_MARKETING", "GEN_SALES", "GEN_DESIGN");
    }

    @Test
    void GitHub_규칙은_개발_필수_데이터_DBA_QA_보안_선택_기획_컨설턴트_숨김이다() {
        assertThat(JobRole.BACKEND.githubRule()).isEqualTo(GithubRule.REQUIRED);
        assertThat(JobRole.WEB_PUBLISHER.githubRule()).isEqualTo(GithubRule.REQUIRED);
        assertThat(JobRole.DATA_ANALYST.githubRule()).isEqualTo(GithubRule.OPTIONAL);
        assertThat(JobRole.SECURITY.githubRule()).isEqualTo(GithubRule.OPTIONAL);
        assertThat(JobRole.PRODUCT_MANAGER.githubRule()).isEqualTo(GithubRule.HIDDEN);
        assertThat(JobRole.IT_CONSULTANT.githubRule()).isEqualTo(GithubRule.HIDDEN);
    }

    @Test
    void 받는_문서_종류는_필수_선택_공통_이력서_경력기술서다() {
        assertThat(JobRole.PRODUCT_MANAGER.requiredTypes()).containsExactly("PRD", "POLICY_DOC", "REQUIREMENT_SPEC");
        assertThat(JobRole.PRODUCT_MANAGER.allowsType("SCREEN_SPEC")).isTrue();
        assertThat(JobRole.PRODUCT_MANAGER.allowsType("RESUME")).isTrue();
        assertThat(JobRole.PRODUCT_MANAGER.allowsType("TEST_CASE")).isFalse();
        assertThat(JobRole.BACKEND.allowsType("PRD")).isFalse();
        assertThat(JobRole.DBA_INFRA.isRequiredType("CAREER_DESCRIPTION")).isTrue();
        assertThat(JobRole.QA_ENGINEER.isRequiredType("RESUME")).isFalse();
    }

    @Test
    void 보완_입력은_기획자와_컨설턴트만_받는다() {
        assertThat(Arrays.stream(JobRole.values()).filter(JobRole::supplementsAllowed))
                .containsExactly(JobRole.PRODUCT_MANAGER, JobRole.IT_CONSULTANT);
    }

    @Test
    void 자료는_개발_3직무_사이에서만_유지한다() {
        assertThat(JobRole.FRONTEND.keepsMaterialsTo(JobRole.BACKEND)).isTrue();
        assertThat(JobRole.BACKEND.keepsMaterialsTo(JobRole.WEB_PUBLISHER)).isTrue();
        assertThat(JobRole.QA_ENGINEER.keepsMaterialsTo(JobRole.QA_ENGINEER)).isTrue();
        assertThat(JobRole.QA_ENGINEER.keepsMaterialsTo(JobRole.SECURITY)).isFalse();
        assertThat(JobRole.BACKEND.keepsMaterialsTo(JobRole.DATA_ANALYST)).isFalse();
        assertThat(JobRole.DATA_ANALYST.keepsMaterialsTo(JobRole.DBA_INFRA)).isFalse();
        assertThat(JobRole.BACKEND.family()).isEqualTo(JobFamily.DEV);
    }

    @Test
    void 코드는_대소문자와_공백을_가리지_않고_모르는_값은_빈_값이다() {
        assertThat(JobRole.find(" qa_engineer ")).contains(JobRole.QA_ENGINEER);
        assertThat(JobRole.find("DESIGNER")).isEmpty();
        assertThat(JobRole.find(null)).isEmpty();
        assertThat(JobRole.QA_ENGINEER.authMajor()).isEqualTo("MAJOR_QA_ENGINEER");
    }

    @Test
    void 일반_6직군은_공통_4문서와_자소서를_받고_GitHub와_보완_입력은_받지_않는다() {
        assertThat(Arrays.stream(JobRole.values()).filter(JobRole::requiresJobTitle))
                .containsExactly(JobRole.GEN_STRATEGY, JobRole.GEN_HR_ADMIN, JobRole.GEN_FINANCE,
                        JobRole.GEN_MARKETING, JobRole.GEN_SALES, JobRole.GEN_DESIGN)
                .allSatisfy(job -> {
                    assertThat(job.family()).isEqualTo(JobFamily.GENERAL);
                    assertThat(job.githubRule()).isEqualTo(GithubRule.HIDDEN);
                    assertThat(job.requiredTypes()).isEmpty();
                    assertThat(job.supplementsAllowed()).isFalse();
                    assertThat(job.coverLettersAllowed()).isTrue();
                    assertThat(job.materialRule()).isEqualTo(JobRole.MaterialRule.NON_RESUME_OR_COVER_LETTER);
                    assertThat(job.allowsType("RESUME")).isTrue();
                    assertThat(job.allowsType("CAREER_DESCRIPTION")).isTrue();
                    assertThat(job.allowsType("PORTFOLIO")).isTrue();
                    assertThat(job.allowsType("WORK_SAMPLE")).isTrue();
                    assertThat(job.keepsMaterialsTo(job)).isTrue();
                });
        assertThat(Arrays.stream(JobRole.values()).filter(job -> !job.requiresJobTitle()))
                .allSatisfy(job -> {
                    assertThat(job.allowsType("WORK_SAMPLE")).isFalse();
                    assertThat(job.coverLettersAllowed()).isFalse();
                    assertThat(job.materialRule()).isEqualTo(JobRole.MaterialRule.REQUIRED_TYPE);
                });
        assertThat(JobRole.GEN_MARKETING.keepsMaterialsTo(JobRole.GEN_HR_ADMIN)).isFalse();
        assertThat(JobRole.GEN_MARKETING.keepsMaterialsTo(JobRole.BACKEND)).isFalse();
    }
}
