package repit.repit_api_server.domain.userdata.interview.dto.request;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.question.entity.enums.Type;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SaveInterviewRequestDeserializationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class));

    /**
     * 채팅 서버 ChatInterviewAllRequest가 보내는 형태 그대로다. 이름이 하나만 어긋나도 그 값은
     * 조용히 null이 되고, 면접 내용이 사라진 채 저장이 끝난다.
     */
    private static final String CHAT_REQUEST = """
            {
              "sessionId": "sess-1",
              "interviewId": 3,
              "userId": 7,
              "status": "COMPLETED",
              "interviewCreatedAt": "2026-08-18T01:00:00",
              "qnaRequests": [
                {
                  "question": {
                    "questionId": 1,
                    "parentId": null,
                    "questionType": "ORIGINAL",
                    "questionIntention": "도입 근거 확인",
                    "questionContent": "WebFlux 를 도입한 이유가 무엇인가요?",
                    "personaId": 5,
                    "questionCreatedAt": "2026-08-18T01:00:00"
                  },
                  "answer": {
                    "questionId": 1,
                    "responseTime": 90,
                    "answerContent": "스레드가 I/O 대기에 묶였습니다.",
                    "answerCreatedAt": "2026-08-18T01:01:30"
                  }
                },
                {
                  "question": {
                    "questionId": -8006038266222948352,
                    "parentId": 1,
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
    void 채팅_서버가_보내는_형태를_그대로_읽는다() {
        contextRunner.run(context -> {
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);

            SaveInterviewRequest request = objectMapper.readValue(CHAT_REQUEST, SaveInterviewRequest.class);

            assertThat(request.getSessionId()).isEqualTo("sess-1");
            assertThat(request.getInterviewId()).isEqualTo(3L);
            assertThat(request.getUserId()).isEqualTo(7L);
            assertThat(request.getStatus()).isEqualTo(Status.COMPLETED);
            assertThat(request.getInterviewCreatedAt())
                    .isEqualTo(LocalDateTime.parse("2026-08-18T01:00:00"));
            assertThat(request.getQnaRequests()).hasSize(2);

            SaveInterviewRequest.Question original = request.getQnaRequests().get(0).getQuestion();
            assertThat(original.getQuestionId()).isEqualTo(1L);
            assertThat(original.getQuestionType()).isEqualTo(Type.ORIGINAL);
            assertThat(original.getPersonaId()).isEqualTo(5L);
            assertThat(original.getQuestionCreatedAt())
                    .isEqualTo(LocalDateTime.parse("2026-08-18T01:00:00"));

            SaveInterviewRequest.Answer answer = request.getQnaRequests().get(0).getAnswer();
            assertThat(answer.getQuestionId()).isEqualTo(1L);
            assertThat(answer.getResponseTime()).isEqualTo(90);
            assertThat(answer.getAnswerContent()).isEqualTo("스레드가 I/O 대기에 묶였습니다.");

            // 꼬리질문 번호는 채팅 서버가 만든 랜덤 음수 long이다. int로는 담기지 않는다.
            SaveInterviewRequest.Question follow = request.getQnaRequests().get(1).getQuestion();
            assertThat(follow.getQuestionId()).isEqualTo(-8006038266222948352L);
            assertThat(follow.getParentId()).isEqualTo(1L);
            assertThat(follow.getQuestionType()).isEqualTo(Type.FOLLOW);

            // 답하지 않고 넘어간 질문은 answer가 null로 온다.
            assertThat(request.getQnaRequests().get(1).getAnswer()).isNull();
        });
    }
}
