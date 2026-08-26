package repit.repit_api_server.domain.userdata.interview.dto.response;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.question.entity.enums.Type;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ChatInterviewAllResponseDeserializationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class));

    /**
     * 채팅 서버가 실제로 내려주는 형태 그대로다. 시각에 오프셋이 없고(LocalDateTime.now()),
     * 면접관은 면접이 아니라 질문에 달려 있으며, 답변에는 번호가 없다.
     */
    private static final String CHAT_RESPONSE = """
            {
              "sessionId": "sess-1",
              "interviewId": 3,
              "userId": 7,
              "status": "COMPLETED",
              "currentQuestionIndex": 2,
              "createdAt": "2026-08-18T01:00:00",
              "qnAResponses": [
                {
                  "question": {
                    "questionId": 31,
                    "parentId": null,
                    "questionType": "ORIGINAL",
                    "questionIntention": "도입 근거 확인",
                    "questionContent": "WebFlux 를 도입한 이유가 무엇인가요?",
                    "personaId": 5,
                    "questionCreatedAt": "2026-08-18T01:00:00"
                  },
                  "answer": {
                    "interviewId": 3,
                    "questionId": 31,
                    "userId": 7,
                    "responseTime": 90,
                    "answerContent": "스레드가 I/O 대기에 묶였습니다.",
                    "answerCreatedAt": "2026-08-18T01:01:30"
                  }
                },
                {
                  "question": {
                    "questionId": 32,
                    "parentId": 31,
                    "questionType": "FOLLOW",
                    "questionIntention": "대안 검토 확인",
                    "questionContent": "가상 스레드는 고려하지 않으셨나요?",
                    "personaId": 5,
                    "questionCreatedAt": "2026-08-18T01:02:00"
                  },
                  "answer": null
                }
              ]
            }
            """;

    @Test
    void 오프셋_없는_시각을_그대로_역직렬화한다() {
        contextRunner.run(context -> {
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);

            ChatInterviewAllResponse response =
                    objectMapper.readValue(CHAT_RESPONSE, ChatInterviewAllResponse.class);

            assertThat(response.getSessionId()).isEqualTo("sess-1");
            assertThat(response.getStatus()).isEqualTo(Status.COMPLETED);
            assertThat(response.getCreatedAt()).isEqualTo(LocalDateTime.parse("2026-08-18T01:00:00"));
            assertThat(response.getQnAResponses()).hasSize(2);

            ChatQuestionResponse first = response.getQnAResponses().get(0).getQuestion();
            assertThat(first.getQuestionType()).isEqualTo(Type.ORIGINAL);
            assertThat(first.getParentId()).isNull();
            assertThat(first.getPersonaId()).isEqualTo(5L);
            assertThat(first.getQuestionCreatedAt()).isEqualTo(LocalDateTime.parse("2026-08-18T01:00:00"));

            ChatAnswerResponse answer = response.getQnAResponses().get(0).getAnswer();
            assertThat(answer.getResponseTime()).isEqualTo(90);
            assertThat(answer.getAnswerCreatedAt()).isEqualTo(LocalDateTime.parse("2026-08-18T01:01:30"));

            // 미답변 문항은 answer가 null로 내려온다.
            assertThat(response.getQnAResponses().get(1).getAnswer()).isNull();
            assertThat(response.getQnAResponses().get(1).getQuestion().getParentId()).isEqualTo(31L);
        });
    }
}
