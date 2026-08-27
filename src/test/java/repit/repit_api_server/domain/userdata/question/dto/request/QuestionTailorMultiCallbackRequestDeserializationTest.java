package repit.repit_api_server.domain.userdata.question.dto.request;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * N:1 질문 구성 콜백.
 *
 * <p>신규 질문 4개는 이 본문이 유일한 원본이다. 여기서 한 필드라도 못 읽으면 되찾을 데가 없다.
 */
class QuestionTailorMultiCallbackRequestDeserializationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class));

    /** 요청에 personaId를 문자열로 실어 보내므로, 그대로 되돌려주는 값도 문자열로 온다. */
    private static final String CALLBACK = """
            {
              "jobId": "job-1",
              "interviewId": "3",
              "status": "succeeded",
              "result": {
                "questions": [
                  { "id": 2, "personaId": "11", "category": "tech_choice",
                    "question": "왜 Redis 를 썼나요?", "expectedAnswer": "캐시 선택 근거와 대안 비교",
                    "basedOn": ["order-api/CacheConfig.java"] },
                  { "id": 6, "personaId": "12", "category": "motivation",
                    "question": "왜 지원하셨나요?", "expectedAnswer": "동기의 구체성", "basedOn": [] }
                ]
              }
            }
            """;

    private static final String FAILED_CALLBACK = """
            {
              "jobId": "job-1",
              "interviewId": "3",
              "status": "failed",
              "error": { "statusCode": 502, "message": "질문 생성에 실패했습니다." }
            }
            """;

    @Test
    void 면접관과_채점_기준까지_읽는다() {
        contextRunner.run(context -> {
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);

            QuestionTailorMultiCallbackRequest request =
                    objectMapper.readValue(CALLBACK, QuestionTailorMultiCallbackRequest.class);

            assertThat(request.getResult().getQuestions()).hasSize(2);
            QuestionTailorMultiCallbackRequest.Question created = request.getResult().getQuestions().get(1);
            assertThat(created.getId()).isEqualTo(6);
            // 문자열로 와도 읽혀야 한다. 못 읽으면 질문이 어느 면접관 것인지 잃는다.
            assertThat(created.getPersonaId()).isEqualTo(12L);
            // 신규 질문의 채점 기준은 이 값뿐이다.
            assertThat(created.getExpectedAnswer()).isEqualTo("동기의 구체성");
            assertThat(request.getResult().getQuestions().getFirst().getBasedOn())
                    .containsExactly("order-api/CacheConfig.java");
        });
    }

    @Test
    void 실패_콜백은_결과_없이_사유만_온다() {
        contextRunner.run(context -> {
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);

            QuestionTailorMultiCallbackRequest request =
                    objectMapper.readValue(FAILED_CALLBACK, QuestionTailorMultiCallbackRequest.class);

            // 1:1의 tailored:false 같은 폴백 플래그가 없다. 어느 단계가 실패하든 여기로 온다.
            assertThat(request.getResult()).isNull();
            assertThat(request.getError().getStatusCode()).isEqualTo(502);
            assertThat(request.getError().getMessage()).isEqualTo("질문 생성에 실패했습니다.");
        });
    }
}
