package repit.repit_api_server.domain.userdata.interview.dto.response;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.persona.entity.PersonaEntity;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Gender;
import repit.repit_api_server.domain.userdata.persona.entity.enums.InterviewTone;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Level;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Role;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Type;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 웹이 읽는 persona 키 이름이 그대로 나가야 한다. 이름이 어긋나면 화면은 조용히 "미제공"이 된다. */
class InterviewResponseSerializationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class));

    private static final InterviewEntity INTERVIEW = InterviewEntity.builder()
            .interviewId(56L)
            .userId(7L)
            .personaId(56L)
            .mode(InterviewMode.SOLO)
            .sessionId("sess-56")
            .status(Status.COMPLETED)
            .build();

    @Test
    void 대표_면접관을_persona_객체로_내보낸다() {
        PersonaEntity persona = PersonaEntity.builder()
                .personaId(56L)
                .personaName("김도윤-4898e87d")
                .role(Role.TECH)
                .type(Type.FRIENDLY)
                .level(Level.EASY)
                .tone(InterviewTone.GENTLE)
                .career(3)
                .gender(Gender.MALE)
                .imageUrl("/img/56.svg")
                .build();

        contextRunner.run(context -> {
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);

            JsonNode json = objectMapper.valueToTree(InterviewResponse.from(INTERVIEW, List.of(), persona));
            JsonNode node = json.get("persona");

            assertThat(node.propertyNames()).containsExactlyInAnyOrder(
                    "id", "personaName", "personaRole", "type", "level", "tone", "imageUrl");
            assertThat(node.get("id").asLong()).isEqualTo(56L);
            assertThat(node.get("personaName").asString()).isEqualTo("김도윤-4898e87d");
            assertThat(node.get("personaRole").asString()).isEqualTo("TECH");
            assertThat(node.get("type").asString()).isEqualTo("FRIENDLY");
            assertThat(node.get("level").asString()).isEqualTo("EASY");
            assertThat(node.get("tone").asString()).isEqualTo("GENTLE");
            assertThat(node.get("imageUrl").asString()).isEqualTo("/img/56.svg");
            // 기존 필드는 그대로 남는다.
            assertThat(json.get("personaId").asLong()).isEqualTo(56L);
        });
    }

    @Test
    void 면접관이_없으면_persona는_null이다() {
        contextRunner.run(context -> {
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);

            JsonNode json = objectMapper.valueToTree(InterviewResponse.from(INTERVIEW, List.of(), null));

            assertThat(json.has("persona")).isTrue();
            assertThat(json.get("persona").isNull()).isTrue();
        });
    }
}
