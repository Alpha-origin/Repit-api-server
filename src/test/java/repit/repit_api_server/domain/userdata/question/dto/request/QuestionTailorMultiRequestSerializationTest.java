package repit.repit_api_server.domain.userdata.question.dto.request;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * N:1 질문 구성 요청 본문. 분석 서버는 camelCase로만 받고, 이름이 어긋나면 422로 거부한다.
 * 프로젝트 요약은 /generate가 snake_case로 내려준 값을 옮겨 싣는 자리라 특히 어긋나기 쉽다.
 */
class QuestionTailorMultiRequestSerializationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class));

    private QuestionTailorMultiRequest request() {
        return QuestionTailorMultiRequest.builder()
                .interviewId("3")
                .userId("7")
                .jobRole("BACKEND")
                .techPersona(QuestionTailorMultiRequest.Persona.builder()
                        .personaId("11")
                        .role("TECH")
                        .style("STRESS")
                        .questionCount(2)
                        .build())
                .otherPersonas(List.of(QuestionTailorMultiRequest.Persona.builder()
                        .personaId("12")
                        .role("HR")
                        .style("FRIENDLY")
                        .questionCount(2)
                        .build()))
                .questions(List.of(QuestionTailorMultiRequest.Question.builder()
                        .id(2)
                        .category("tech_choice")
                        .question("왜 Redis 를 썼나요?")
                        .expectedAnswer("캐시 계층 선택 근거와 대안 비교")
                        .basedOn(List.of("order-api/CacheConfig.java"))
                        .build()))
                .projectSummary(QuestionTailorMultiRequest.ProjectSummary.builder()
                        .overview("주문 처리를 맡는 백엔드")
                        .repositories(List.of(QuestionTailorMultiRequest.Repository.builder()
                                .repo("order-api")
                                .role("api_server")
                                .description("주문 API")
                                .build()))
                        .coreFeatures(List.of(QuestionTailorMultiRequest.CoreFeature.builder()
                                .name("주문 생성")
                                .description("결제 승인 후 주문을 만든다")
                                .basedOn(List.of("order-api/OrderService.java"))
                                .build()))
                        .techStack(List.of("Spring", "Redis"))
                        .build())
                .callbackUrl("https://example.com/api/questions/tailor/multi/callback")
                .build();
    }

    @Test
    void 요청은_camelCase로_직렬화된다() {
        contextRunner.run(context -> {
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);

            String json = objectMapper.writeValueAsString(request());

            assertThat(json).contains("\"interviewId\":\"3\"");
            assertThat(json).contains("\"techPersona\":");
            assertThat(json).contains("\"otherPersonas\":");
            assertThat(json).contains("\"questionCount\":2");
            assertThat(json).contains("\"expectedAnswer\":");
            assertThat(json).contains("\"projectSummary\":");
            assertThat(json).contains("\"coreFeatures\":");
            assertThat(json).contains("\"techStack\":[\"Spring\",\"Redis\"]");
            // /generate가 내려준 snake_case가 그대로 새어나가면 근거 없이 질문이 만들어진다.
            assertThat(json).doesNotContain("core_features");
            assertThat(json).doesNotContain("tech_stack");
            assertThat(json).doesNotContain("based_on");
            assertThat(json).doesNotContain("expected_answer");
        });
    }

    @Test
    void 면접관_식별자는_문자열로_나간다() {
        contextRunner.run(context -> {
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);

            JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(request()));

            // 분석 서버는 personaId를 문자열로 받는다. 숫자로 보내면 422다.
            assertThat(json.get("techPersona").get("personaId").isString()).isTrue();
            assertThat(json.get("otherPersonas").get(0).get("personaId").isString()).isTrue();
        });
    }
}
