package repit.repit_api_server.domain.userdata.interview.dto.response;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.question.entity.enums.Type;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ChatInterviewAllResponseDeserializationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class));

    // 채팅 서버는 createdAt을 UTC 오프셋(Z)이 붙은 ISO 8601로 내려준다.
    private static final String CHAT_RESPONSE = """
            {
              "sessionId": "sess-1",
              "interviewId": 3,
              "userId": 7,
              "personaId": 1,
              "personaType": "NEUTRAL",
              "status": "COMPLETED",
              "currentQuestionIndex": 2,
              "createdAt": "2026-08-18T01:00:00Z",
              "qnAResponses": [
                {
                  "question": {
                    "questionId": 31,
                    "parentId": null,
                    "questionType": "ORIGINAL",
                    "questionIntention": "도입 근거 확인",
                    "questionContent": "WebFlux 를 도입한 이유가 무엇인가요?",
                    "questionCreatedAt": "2026-08-18T01:00:00Z"
                  },
                  "answer": {
                    "answerId": 41,
                    "interviewId": 3,
                    "questionId": 31,
                    "userId": 7,
                    "responseTime": 90,
                    "answerContent": "스레드가 I/O 대기에 묶였습니다.",
                    "answerCreatedAt": "2026-08-18T01:01:30Z"
                  }
                },
                {
                  "question": {
                    "questionId": 32,
                    "parentId": 31,
                    "questionType": "FOLLOW",
                    "questionIntention": "대안 검토 확인",
                    "questionContent": "가상 스레드는 고려하지 않으셨나요?",
                    "questionCreatedAt": "2026-08-18T01:02:00Z"
                  },
                  "answer": null
                }
              ]
            }
            """;

    @Test
    void Z가_붙은_시각을_그대로_역직렬화한다() {
        contextRunner.run(context -> {
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);

            ChatInterviewAllResponse response =
                    objectMapper.readValue(CHAT_RESPONSE, ChatInterviewAllResponse.class);

            assertThat(response.getSessionId()).isEqualTo("sess-1");
            assertThat(response.getStatus()).isEqualTo(Status.COMPLETED);
            assertThat(response.getCreatedAt().getOffset()).isEqualTo(ZoneOffset.UTC);
            assertThat(response.getQnAResponses()).hasSize(2);

            ChatQuestionResponse first = response.getQnAResponses().get(0).getQuestion();
            assertThat(first.getQuestionType()).isEqualTo(Type.ORIGINAL);
            assertThat(first.getParentId()).isNull();
            assertThat(first.getQuestionCreatedAt()).isEqualTo(OffsetDateTime.parse("2026-08-18T01:00:00Z"));

            ChatAnswerResponse answer = response.getQnAResponses().get(0).getAnswer();
            assertThat(answer.getAnswerCreatedAt()).isEqualTo(OffsetDateTime.parse("2026-08-18T01:01:30Z"));

            // 미답변 문항은 answer가 null로 내려온다.
            assertThat(response.getQnAResponses().get(1).getAnswer()).isNull();
            assertThat(response.getQnAResponses().get(1).getQuestion().getParentId()).isEqualTo(31L);
        });
    }
}
