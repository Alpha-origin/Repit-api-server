package repit.repit_api_server.domain.userdata.interview.dto.request;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class CreateInterviewRequestDeserializationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class));

    @Test
    void 페르소나_전체를_보내던_기존_웹_요청도_그대로_받는다() {
        contextRunner.run(context -> {
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);

            // 예전 DTO(PersonaRequest) 형태. 쓰지 않는 필드가 섞여 들어와도 400이 나면 안 된다.
            String json = """
                    {
                      "personaName": "압박 면접관",
                      "major": "BACKEND",
                      "type": "STRESS",
                      "career": 10,
                      "gender": "MALE"
                    }
                    """;

            CreateInterviewRequest request = objectMapper.readValue(json, CreateInterviewRequest.class);

            assertThat(request.getPersonaName()).isEqualTo("압박 면접관");
            assertThat(request.getPersonaId()).isNull();
        });
    }

    @Test
    void personaId만_보내는_요청도_받는다() {
        contextRunner.run(context -> {
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);

            CreateInterviewRequest request = objectMapper.readValue(
                    "{\"personaId\": 1}", CreateInterviewRequest.class);

            assertThat(request.getPersonaId()).isEqualTo(1L);
            assertThat(request.getPersonaName()).isNull();
        });
    }
}
