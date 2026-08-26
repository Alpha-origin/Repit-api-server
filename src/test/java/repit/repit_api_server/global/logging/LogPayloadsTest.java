package repit.repit_api_server.global.logging;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class LogPayloadsTest {

    @Test
    void 여러_줄_본문을_한_줄로_눕힌다() {
        byte[] body = "{\n  \"interviewId\": 3\n}".getBytes(StandardCharsets.UTF_8);

        String summary = LogPayloads.summarize(body, "application/json", StandardCharsets.UTF_8, 100);

        assertThat(summary).isEqualTo("{ \"interviewId\": 3 }");
    }

    @Test
    void 비밀번호나_토큰_값은_가린다() {
        byte[] body = "{\"email\":\"a@b.com\",\"password\":\"1234\",\"accessToken\":\"eyJhbGc\"}".getBytes(StandardCharsets.UTF_8);

        String summary = LogPayloads.summarize(body, "application/json", StandardCharsets.UTF_8, 500);

        assertThat(summary)
                .contains("a@b.com")
                .doesNotContain("1234")
                .doesNotContain("eyJhbGc")
                .contains(LogPayloads.MASKED);
    }

    @Test
    void 폼_본문의_민감한_값도_가린다() {
        byte[] body = "userId=7&password=secret1&next=/home".getBytes(StandardCharsets.UTF_8);

        String summary = LogPayloads.summarize(body, "application/x-www-form-urlencoded", StandardCharsets.UTF_8, 500);

        assertThat(summary).contains("userId=7").doesNotContain("secret1").contains("next=/home");
    }

    @Test
    void 최대_길이를_넘으면_잘라내고_원본_크기를_덧붙인다() {
        byte[] body = "a".repeat(3000).getBytes(StandardCharsets.UTF_8);

        String summary = LogPayloads.summarize(body, "application/json", StandardCharsets.UTF_8, 100);

        assertThat(summary).startsWith("a".repeat(100)).contains("...(총 ").contains("KB)");
    }

    @Test
    void 읽을_수_없는_형식은_크기만_남긴다() {
        byte[] body = new byte[2048];

        String summary = LogPayloads.summarize(body, "image/png", StandardCharsets.UTF_8, 500);

        assertThat(summary).isEqualTo("(image/png, 2.0KB)");
    }

    @Test
    void 빈_본문은_남길_것이_없다() {
        assertThat(LogPayloads.summarize(new byte[0], "application/json", StandardCharsets.UTF_8, 500)).isNull();
        assertThat(LogPayloads.summarize(null, "application/json", StandardCharsets.UTF_8, 500)).isNull();
    }

    @Test
    void 인증_헤더는_방식만_남기고_값을_가린다() {
        assertThat(LogPayloads.maskAuthorization("Bearer eyJhbGciOiJIUzI1NiJ9")).isEqualTo("Bearer " + LogPayloads.MASKED);
        assertThat(LogPayloads.maskAuthorization("eyJhbGciOiJIUzI1NiJ9")).isEqualTo(LogPayloads.MASKED);
        assertThat(LogPayloads.maskAuthorization(null)).isNull();
    }
}
