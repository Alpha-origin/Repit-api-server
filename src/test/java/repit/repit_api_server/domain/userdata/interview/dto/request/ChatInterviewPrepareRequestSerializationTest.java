package repit.repit_api_server.domain.userdata.interview.dto.request;

import org.junit.jupiter.api.Test;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.persona.entity.enums.InterviewTone;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Level;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Major;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Type;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 채팅 서버가 받는 형태 그대로 직렬화되는지 고정한다.
 * 이름이 하나만 어긋나도 채팅 서버는 그 값을 조용히 버리고, 면접은 빈 값으로 열린다.
 */
class ChatInterviewPrepareRequestSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void 채팅_서버가_받는_이름으로_나간다() {
        ChatInterviewPrepareRequest request = ChatInterviewPrepareRequest.builder()
                .sessionId("sess-1")
                .interviewId(1L)
                .userId(1L)
                .status(Status.IN_PROGRESS)
                .mode(InterviewMode.SOLO)
                .personality(Type.METICULOUS)
                .tone(InterviewTone.PRESSURING)
                .major(Major.BACKEND)
                .level(Level.HARD)
                .questions(List.of(ChatInterviewPrepareRequest.Question.builder()
                        .id(1L)
                        .category("tech_choice")
                        .question("왜 Redis 를 썼나요?")
                        .expectedAnswer("선택 근거와 대안 비교")
                        .basedOn(List.of("order-api/src/cache.py"))
                        .personaId(9L)
                        .build()))
                .build();

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(request));

        assertThat(json.propertyNames()).containsExactlyInAnyOrder(
                "sessionId", "interviewId", "userId", "status", "mode",
                "personality", "tone", "major", "level", "questions");
        assertThat(json.get("status").asString()).isEqualTo("IN_PROGRESS");
        // 면접 방식은 이름 그대로 나가야 채팅 서버가 읽는다.
        assertThat(json.get("mode").asString()).isEqualTo("SOLO");
        // 면접관 설정 네 가지. 채팅 서버가 필수로 받아 하나라도 비면 면접이 열리지 않는다.
        assertThat(json.get("personality").asString()).isEqualTo("METICULOUS");
        assertThat(json.get("tone").asString()).isEqualTo("PRESSURING");
        assertThat(json.get("major").asString()).isEqualTo("BACKEND");
        assertThat(json.get("level").asString()).isEqualTo("HARD");

        JsonNode question = json.get("questions").get(0);
        assertThat(question.propertyNames()).containsExactlyInAnyOrder(
                "id", "category", "question", "expectedAnswer", "basedOn", "personaId");
        assertThat(question.get("id").asLong()).isEqualTo(1L);
        assertThat(question.get("category").asString()).isEqualTo("tech_choice");
        assertThat(question.get("question").asString()).isEqualTo("왜 Redis 를 썼나요?");
        // 채점 기준이다. 이름이 어긋나면 채팅 서버가 버리고, 기록이 돌아와도 비어 있다.
        assertThat(question.get("expectedAnswer").asString()).isEqualTo("선택 근거와 대안 비교");
        assertThat(question.get("basedOn").get(0).asString()).isEqualTo("order-api/src/cache.py");
        assertThat(question.get("personaId").asLong()).isEqualTo(9L);
    }

    @Test
    void N대1_면접은_MULTI로_나간다() {
        ChatInterviewPrepareRequest request = ChatInterviewPrepareRequest.builder()
                .sessionId("sess-2")
                .interviewId(2L)
                .userId(1L)
                .status(Status.IN_PROGRESS)
                .mode(InterviewMode.MULTI)
                .personality(Type.FRIENDLY)
                .tone(InterviewTone.GENTLE)
                .major(Major.FRONTEND)
                .level(Level.EASY)
                .questions(List.of())
                .build();

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(request));

        assertThat(json.get("mode").asString()).isEqualTo("MULTI");
    }
}
