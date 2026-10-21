package repit.repit_api_server.domain.userdata.feedback.dto.request;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import repit.repit_api_server.domain.userdata.question.entity.enums.Type;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FeedbackSoloRequestSerializationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(JacksonAutoConfiguration.class));

    @Test
    void createdAt은_ISO8601_UTC_문자열로_직렬화된다() {
        contextRunner.run(context -> {
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);

            LocalDateTime createdAt = LocalDateTime.of(2026, 8, 18, 10, 0, 0);
            FeedbackSoloRequest request = FeedbackSoloRequest.builder()
                    .interviewStatus("COMPLETED")
                    .sessionId("sess-1")
                    .interviewId("1")
                    .userId("7")
                    .personaType("REALISTIC")
                    .callbackUrl("https://example.com/api/feedbacks/callback")
                    .questions(List.of(FeedbackSoloRequest.Question.builder()
                            .questionId("11")
                            .parentId(null)
                            .type(Type.ORIGINAL)
                            .intention("의도")
                            .content("질문")
                            .createdAt(createdAt.atZone(ZoneId.of("Asia/Seoul"))
                                    .toOffsetDateTime()
                                    .withOffsetSameInstant(ZoneOffset.UTC))
                            .build()))
                    .answers(List.of())
                    .build();

            String json = objectMapper.writeValueAsString(request);

            // KST 10:00 -> UTC 01:00, 숫자 배열이 아닌 ISO 문자열이어야 한다.
            assertThat(json).contains("\"createdAt\":\"2026-08-18T01:00:00Z\"");
            assertThat(json).contains("\"type\":\"ORIGINAL\"");
            assertThat(json).contains("\"parentId\":null");
        });
    }

    @Test
    void 직무와_직무명은_있을_때만_직렬화된다() {
        contextRunner.run(context -> {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            String empty = mapper.writeValueAsString(FeedbackSoloRequest.builder().build());
            assertThat(empty).doesNotContain("jobRole", "jobTitle");
            String it = mapper.writeValueAsString(FeedbackSoloRequest.builder().jobRole("DATA_ANALYST").build());
            assertThat(it).contains("\"jobRole\":\"DATA_ANALYST\"").doesNotContain("jobTitle");
            String general = mapper.writeValueAsString(FeedbackSoloRequest.builder()
                    .jobRole("GEN_MARKETING").jobTitle("브랜드 마케터").build());
            assertThat(general).contains("\"jobRole\":\"GEN_MARKETING\"", "\"jobTitle\":\"브랜드 마케터\"");
        });
    }

    /** 카테고리가 없는 질문은 키 자체를 보내지 않는다. 분석 서버는 키가 없으면 미분류로 채점한다. */
    @Test
    void 질문_카테고리는_있을_때만_직렬화된다() {
        contextRunner.run(context -> {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            FeedbackSoloRequest request = FeedbackSoloRequest.builder()
                    .questions(List.of(
                            FeedbackSoloRequest.Question.builder().questionId("11").category("tech_choice").build(),
                            FeedbackSoloRequest.Question.builder().questionId("12").build()))
                    .build();

            JsonNode questions = mapper.readTree(mapper.writeValueAsString(request)).get("questions");

            assertThat(questions.get(0).get("category").stringValue()).isEqualTo("tech_choice");
            assertThat(questions.get(1).has("category")).isFalse();
        });
    }

    @Test
    void 면접_상태는_interviewStatus_키로_직렬화된다() {
        contextRunner.run(context -> {
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);

            FeedbackSoloRequest request = FeedbackSoloRequest.builder()
                    .interviewStatus("COMPLETED")
                    .sessionId("sess-1")
                    .interviewId("1")
                    .userId("7")
                    .questions(List.of())
                    .answers(List.of())
                    .build();

            JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(request));

            assertThat(json.get("interviewStatus").stringValue()).isEqualTo("COMPLETED");
        });
    }
}
