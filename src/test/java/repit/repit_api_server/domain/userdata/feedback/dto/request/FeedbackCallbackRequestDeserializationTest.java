package repit.repit_api_server.domain.userdata.feedback.dto.request;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** N:1 콜백은 1:1과 같은 엔드포인트로 들어온다. 늘어난 personas 계층이 그대로 읽히는지 확인한다. */
class FeedbackCallbackRequestDeserializationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class));

    private static final String MULTI_CALLBACK = """
            {
              "jobId": "job-1",
              "sessionId": "sess-1",
              "status": "succeeded",
              "result": {
                "overall": {
                  "totalScore": 72,
                  "intentAlignmentScore": 80,
                  "reliabilityScore": 61,
                  "summary": "기술 질문에는 근거를 들어 답했으나, 면접관이 바뀐 뒤 설명이 달라졌습니다.",
                  "strengths": ["캐시 도입 배경을 트래픽 수치와 함께 설명함"],
                  "improvements": ["기술 부문에서는 팀 합의로, CEO 질문에서는 개인 판단으로 설명이 엇갈림"],
                  "frequentWords": [{ "word": "성능", "count": 7 }],
                  "answeredCount": 6,
                  "questionCount": 7
                },
                "personas": [
                  {
                    "personaId": 11,
                    "personaRole": "TECH",
                    "score": 78,
                    "comment": "선택 근거는 분명하나 대안 검토가 얕습니다.",
                    "strengths": ["측정값을 근거로 제시함"],
                    "improvements": ["고려했다 밝힌 대안의 탈락 이유가 없음"],
                    "answeredCount": 3,
                    "questionCount": 3
                  },
                  { "personaId": 12, "personaRole": "HR", "score": 70 },
                  { "personaId": 13, "personaRole": "CEO", "score": 64 }
                ],
                "feedbacks": [
                  {
                    "questionId": "2",
                    "personaId": 11,
                    "questionContent": "주문 API에서 Redis를 캐시로 두신 이유는?",
                    "intention": "기술 선택의 근거와 트레이드오프 인식",
                    "userAnswer": "조회가 쓰기보다 많아서 앞단에 뒀습니다.",
                    "modelAnswer": "조회 비중과 응답 지연을 수치로 제시하고 무효화 전략까지 밝힌다.",
                    "strengths": ["p99 지연을 근거로 든 점"],
                    "improvements": ["캐시 무효화 전략을 언급하지 않음"],
                    "comment": "선택 이유는 설득력 있으나 운영 시 부작용까지는 못 짚었습니다."
                  }
                ]
              }
            }
            """;

    // 1:1 콜백에는 personas가 아예 없다. 그래도 같은 DTO로 읽혀야 한다.
    private static final String SOLO_CALLBACK = """
            {
              "jobId": "job-2",
              "sessionId": "sess-2",
              "status": "succeeded",
              "result": {
                "overall": { "totalScore": 55, "answeredCount": 5, "questionCount": 7 },
                "feedbacks": [{ "questionId": "1", "comment": "근거가 부족합니다." }]
              }
            }
            """;

    @Test
    void 면접관별_종합과_문항의_면접관을_읽는다() {
        contextRunner.run(context -> {
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);

            FeedbackCallbackRequest request =
                    objectMapper.readValue(MULTI_CALLBACK, FeedbackCallbackRequest.class);

            assertThat(request.getResult().getOverall().getReliabilityScore()).isEqualTo(61);

            assertThat(request.getResult().getPersonas()).hasSize(3);
            FeedbackCallbackRequest.Persona tech = request.getResult().getPersonas().getFirst();
            assertThat(tech.getPersonaId()).isEqualTo(11L);
            assertThat(tech.getPersonaRole()).isEqualTo("TECH");
            assertThat(tech.getScore()).isEqualTo(78);
            assertThat(tech.getStrengths()).containsExactly("측정값을 근거로 제시함");
            assertThat(tech.getQuestionCount()).isEqualTo(3);

            FeedbackCallbackRequest.Item item = request.getResult().getFeedbacks().getFirst();
            assertThat(item.getQuestionId()).isEqualTo("2");
            assertThat(item.getPersonaId()).isEqualTo(11L);
        });
    }

    @Test
    void 일대일_콜백은_personas_없이도_읽힌다() {
        contextRunner.run(context -> {
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);

            FeedbackCallbackRequest request =
                    objectMapper.readValue(SOLO_CALLBACK, FeedbackCallbackRequest.class);

            assertThat(request.getResult().getPersonas()).isNull();
            assertThat(request.getResult().getFeedbacks().getFirst().getPersonaId()).isNull();
        });
    }
}
