package repit.repit_api_server.domain.userdata.interview.dto.request;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
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
                    "id": 1,
                    "personaId": 5,
                    "category": "tech_choice",
                    "question": "WebFlux 를 도입한 이유가 무엇인가요?",
                    "expectedAnswer": "블로킹 I/O 병목과 대안 비교",
                    "basedOn": ["order-api/src/router.java"]
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
                    "id": -1,
                    "personaId": 5,
                    "category": "대안 검토 확인",
                    "question": "가상 스레드는 고려하지 않으셨나요?",
                    "expectedAnswer": null,
                    "basedOn": null
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
            assertThat(original.getId()).isEqualTo(1L);
            assertThat(original.getPersonaId()).isEqualTo(5L);
            assertThat(original.getCategory()).isEqualTo("tech_choice");
            assertThat(original.getQuestion()).isEqualTo("WebFlux 를 도입한 이유가 무엇인가요?");
            // 면접을 열 때 우리가 넘긴 채점 기준이 그대로 돌아온다.
            assertThat(original.getExpectedAnswer()).isEqualTo("블로킹 I/O 병목과 대안 비교");
            assertThat(original.getBasedOn()).containsExactly("order-api/src/router.java");

            SaveInterviewRequest.Answer answer = request.getQnaRequests().get(0).getAnswer();
            assertThat(answer.getQuestionId()).isEqualTo(1L);
            assertThat(answer.getResponseTime()).isEqualTo(90);
            assertThat(answer.getAnswerContent()).isEqualTo("스레드가 I/O 대기에 묶였습니다.");

            // 꼬리질문은 채팅 서버가 음수로 번호를 매긴다. 우리가 넘긴 적 없는 질문이라 기대 답변이 없다.
            SaveInterviewRequest.Question follow = request.getQnaRequests().get(1).getQuestion();
            assertThat(follow.getId()).isEqualTo(-1L);
            assertThat(follow.getCategory()).isEqualTo("대안 검토 확인");
            assertThat(follow.getExpectedAnswer()).isNull();

            // 답하지 않고 넘어간 질문은 answer가 null로 온다.
            assertThat(request.getQnaRequests().get(1).getAnswer()).isNull();
        });
    }
}
