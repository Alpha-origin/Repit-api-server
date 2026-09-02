package repit.repit_api_server.domain.userdata.question.dto.request;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QuestionTailorRequestSerializationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class));

    @Test
    void 요청은_camelCase로_직렬화된다() {
        contextRunner.run(context -> {
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);

            QuestionTailorRequest request = QuestionTailorRequest.builder()
                    .interviewId("3")
                    .userId("7")
                    .profile(QuestionTailorRequest.Profile.builder()
                            .jobRole("BACKEND")
                            .personaType("METICULOUS")
                            .build())
                    .questions(List.of(QuestionTailorRequest.Question.builder()
                            .id(1)
                            .category("tech_choice")
                            .question("왜 Redis 를 썼나요?")
                            .expectedAnswer("캐시 계층 선택 근거와 대안 비교")
                            .basedOn(List.of("order-api/src/cache.py"))
                            .build()))
                    .callbackUrl("https://example.com/api/questions/tailor/callback")
                    .build();

            String json = objectMapper.writeValueAsString(request);

            // snake_case로 새어나가면 분석 서버가 422로 거부한다.
            assertThat(json).contains("\"interviewId\":\"3\"");
            assertThat(json).contains("\"expectedAnswer\":\"캐시 계층 선택 근거와 대안 비교\"");
            assertThat(json).contains("\"basedOn\":[\"order-api/src/cache.py\"]");
            assertThat(json).contains("\"callbackUrl\":");
            assertThat(json).doesNotContain("expected_answer");
            assertThat(json).doesNotContain("based_on");
        });
    }
}
