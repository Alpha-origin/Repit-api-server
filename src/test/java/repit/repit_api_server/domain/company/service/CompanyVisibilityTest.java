package repit.repit_api_server.domain.company.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SAMPLE 허용 설정. 비워 두면 운영 프로필이 아닐 때만 허용한다. */
class CompanyVisibilityTest {

    @Test
    void 비워_두면_운영이_아닐_때만_허용한다() {
        assertThat(CompanyVisibility.resolve(null, false)).isTrue();
        assertThat(CompanyVisibility.resolve("  ", false)).isTrue();
        assertThat(CompanyVisibility.resolve("", true)).isFalse();
    }

    @Test
    void 값을_넣으면_프로필과_상관없이_그_값을_쓴다() {
        assertThat(CompanyVisibility.resolve("true", true)).isTrue();
        assertThat(CompanyVisibility.resolve(" FALSE ", false)).isFalse();
    }

    @Test
    void true_false가_아닌_값은_서버를_띄우지_않는다() {
        assertThatThrownBy(() -> CompanyVisibility.resolve("yes", false)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 운영_프로필이_켜져_있으면_기본으로_막는다() {
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("prod");

        assertThat(new CompanyVisibility("", production).allowsSample()).isFalse();
        assertThat(new CompanyVisibility("", new MockEnvironment()).allowsSample()).isTrue();
        assertThat(new CompanyVisibility("true", production).allowsSample()).isTrue();
    }
}
