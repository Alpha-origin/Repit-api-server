package repit.repit_api_server.global.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 빠지면 서버가 제대로 일하지 못하는 설정을 기동 시점에 막는지 본다(N-6). */
class RequiredSettingsCheckTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RequiredSettingsCheck.class);

    @Test
    void 콜백_주소가_없거나_비면_기동하지_않는다() {
        runner.run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining("CALLBACK_BASE_URL"));
        runner.withPropertyValues("app.callback-base-url=  ")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void 형식이_틀린_콜백_주소는_받지_않는다() {
        for (String url : List.of("api.repit.example", "ftp://api.repit.example", "https://",
                "https://user:pw@api.repit.example", "https://api.repit.example?env=dev",
                "https://api.repit.example#top", "https://api.repit.example/", "https://api repit.example")) {
            assertThatThrownBy(() -> RequiredSettingsCheck.verifyCallbackBaseUrl(url, false))
                    .as(url)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("CALLBACK_BASE_URL");
        }
    }

    /** 로컬은 ngrok이나 localhost로 개발한다. 운영 프로필이 아닐 때는 받는다. */
    @Test
    void 운영이_아니면_개발용_주소를_받는다() {
        for (String url : List.of("https://wildcat-startle-rope.ngrok-free.dev", "http://localhost:8080",
                "http://127.0.0.1:8080", "https://api.repit.example/base")) {
            assertThatCode(() -> RequiredSettingsCheck.verifyCallbackBaseUrl(url, false)).as(url)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void 운영에서는_https가_아니거나_개발용_주소면_받지_않는다() {
        for (String url : List.of("https://wildcat-startle-rope.ngrok-free.dev", "https://abc.ngrok.io",
                "https://localhost", "https://api.localhost", "https://127.0.0.1", "https://[::1]",
                "http://api.repit.example")) {
            assertThatThrownBy(() -> RequiredSettingsCheck.verifyCallbackBaseUrl(url, true)).as(url)
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThatCode(() -> RequiredSettingsCheck.verifyCallbackBaseUrl("https://api.repit.example", true))
                .doesNotThrowAnyException();
    }

    @Test
    void 운영_프로필에서_ngrok_주소면_기동하지_않는다() {
        runner.withPropertyValues("spring.profiles.active=prod",
                        "app.callback-base-url=https://wildcat-startle-rope.ngrok-free.dev")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("spring.profiles.active=prod", "app.callback-base-url=https://api.repit.example")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void 내부_인증을_필수로_켰는데_인증값이_없으면_기동하지_않는다() {
        runner.withPropertyValues("app.callback-base-url=https://api.repit.example",
                        "app.internal-auth.required=true", "app.internal-auth.token= ")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("INTERNAL_AUTH_TOKEN"));
    }

    @Test
    void 내부_인증이_필수가_아니면_인증값_없이도_기동한다() {
        runner.withPropertyValues("app.callback-base-url=https://api.repit.example")
                .run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("app.callback-base-url=https://api.repit.example",
                        "app.internal-auth.required=true", "app.internal-auth.token=secret-token-value")
                .run(context -> assertThat(context).hasNotFailed());
    }

    /** 사용자 정보·쿼리를 막는 검사라, 원문을 오류에 실으면 그 안의 자격 증명이 기동 실패 로그에 남는다. */
    @Test
    void 오류_메시지에_주소_원문을_싣지_않는다() {
        assertThatThrownBy(() -> RequiredSettingsCheck.verifyCallbackBaseUrl(
                "https://user:secret-password@api.repit.example?token=query-secret", false))
                .isInstanceOf(IllegalStateException.class)
                .message()
                .doesNotContain("secret-password", "query-secret", "user:")
                .contains("api.repit.example");
    }
}
