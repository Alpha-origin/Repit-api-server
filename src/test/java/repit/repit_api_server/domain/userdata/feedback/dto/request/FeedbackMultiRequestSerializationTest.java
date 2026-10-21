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
                .interviewStatus("COMPLETED")
                .sessionId("sess-1")
                .interviewId("3")
                .userId("7")
                .personas(List.of(
                        FeedbackMultiRequest.Persona.builder()
                                .personaId("11").role("TECH").style("METICULOUS").build(),
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
    void 질문_카테고리는_있을_때만_직렬화된다() {
        contextRunner.run(context -> {
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);
            FeedbackMultiRequest request = FeedbackMultiRequest.builder()
                    .questions(List.of(
                            FeedbackMultiRequest.Question.builder().questionId("901").category("motivation").build(),
                            FeedbackMultiRequest.Question.builder().questionId("902").build()))
                    .build();

            JsonNode questions = objectMapper.readTree(objectMapper.writeValueAsString(request)).get("questions");

            assertThat(questions.get(0).get("category").stringValue()).isEqualTo("motivation");
            assertThat(questions.get(1).has("category")).isFalse();
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

    @Test
    void 직무와_직무명은_있을_때만_직렬화된다() {
        contextRunner.run(context -> {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            String empty = mapper.writeValueAsString(FeedbackMultiRequest.builder().build());
            assertThat(empty).doesNotContain("jobRole", "jobTitle");
            String it = mapper.writeValueAsString(FeedbackMultiRequest.builder().jobRole("DATA_ANALYST").build());
            assertThat(it).contains("\"jobRole\":\"DATA_ANALYST\"").doesNotContain("jobTitle");
            String general = mapper.writeValueAsString(FeedbackMultiRequest.builder()
                    .jobRole("GEN_MARKETING").jobTitle("브랜드 마케터").build());
            assertThat(general).contains("\"jobRole\":\"GEN_MARKETING\"", "\"jobTitle\":\"브랜드 마케터\"");
        });
    }

    @Test
    void 면접_상태는_interviewStatus_키로_직렬화된다() {
        contextRunner.run(context -> {
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);

            JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(request()));

            assertThat(json.get("interviewStatus").stringValue()).isEqualTo("COMPLETED");
        });
    }
}
