package repit.repit_api_server.domain.userdata.feedback.dto.request;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import repit.repit_api_server.domain.userdata.question.entity.enums.Type;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** N:1 채점 요청 본문. 1:1과 다른 것은 personas 명단과 질문별 personaId 두 가지다. */
class FeedbackMultiRequestSerializationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class));

    private FeedbackMultiRequest request() {
        return FeedbackMultiRequest.builder()
                .sessionId("sess-1")
                .interviewId("3")
                .userId("7")
                .personas(List.of(
                        FeedbackMultiRequest.Persona.builder()
                                .personaId("11").role("TECH").style("STRESS").build(),
                        FeedbackMultiRequest.Persona.builder()
                                .personaId("12").role("HR").style("FRIENDLY").build()))
                .questions(List.of(FeedbackMultiRequest.Question.builder()
                        .questionId("901")
                        .personaId("11")
                        .parentId(null)
                        .type(Type.ORIGINAL)
                        .intention("기술 선택의 근거 확인")
                        .content("왜 Redis 를 썼나요?")
                        .createdAt(OffsetDateTime.parse("2026-08-26T10:00:00Z"))
                        .build()))
                .answers(List.of(FeedbackMultiRequest.Answer.builder()
                        .answerId("501")
                        .questionId("901")
                        .content("조회가 쓰기보다 많아서입니다.")
                        .createdAt(OffsetDateTime.parse("2026-08-26T10:01:00Z"))
                        .build()))
                .callbackUrl("https://example.com/api/feedbacks/callback")
                .build();
    }

    @Test
    void 질문마다_면접관이_실린다() {
        contextRunner.run(context -> {
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);

            JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(request()));

            // 이 값이 비면 분석 서버가 요청 전체를 422로 거부한다.
            assertThat(json.get("questions").get(0).get("personaId").stringValue()).isEqualTo("11");
            assertThat(json.get("personas").get(1).get("role").stringValue()).isEqualTo("HR");
            assertThat(json.get("personas").get(0).get("personaId").isString()).isTrue();
        });
    }

    @Test
    void 시각은_ISO_8601_UTC로_나간다() {
        contextRunner.run(context -> {
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);

            String json = objectMapper.writeValueAsString(request());

            assertThat(json).contains("\"createdAt\":\"2026-08-26T10:00:00Z\"");
            assertThat(json).doesNotContain("created_at");
        });
    }
}
