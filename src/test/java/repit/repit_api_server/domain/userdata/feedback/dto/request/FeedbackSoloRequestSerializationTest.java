package repit.repit_api_server.domain.userdata.feedback.dto.request;

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
                    .sessionId("sess-1")
                    .interviewId("1")
                    .userId("7")
                    .personaType("NEUTRAL")
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
}
