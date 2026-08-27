package repit.repit_api_server.domain.metadata.dto.request;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class CallbackSuccessRequestDeserializationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class));

    // /generate 콜백의 작업 식별자는 jobId다. 실패면 result 없이 error만 온다.
    private static final String FAILURE_CALLBACK = """
            {
              "jobId": "3f0a",
              "status": "failed",
              "error": { "status_code": 403, "message": "private 저장소입니다." }
            }
            """;

    @Test
    void 실패_콜백의_error를_읽는다() {
        contextRunner.run(context -> {
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);

            CallbackSuccessRequest request = objectMapper.readValue(FAILURE_CALLBACK, CallbackSuccessRequest.class);

            assertThat(request.getJobId()).isEqualTo("3f0a");
            assertThat(request.getStatus()).isEqualTo("failed");
            assertThat(request.getResult()).isNull();
            assertThat(request.getError().getStatus_code()).isEqualTo(403);
            assertThat(request.getError().getMessage()).isEqualTo("private 저장소입니다.");
        });
    }
}
