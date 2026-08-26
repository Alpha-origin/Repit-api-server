package repit.repit_api_server.domain.userdata.interview.dto.request;

import org.junit.jupiter.api.Test;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
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
                .questions(List.of(ChatInterviewPrepareRequest.Question.builder()
                        .id(1L)
                        .category("선택 근거와 대안 비교")
                        .question("왜 Redis 를 썼나요?")
                        .personaId(9L)
                        .build()))
                .build();

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(request));

        assertThat(json.propertyNames()).containsExactlyInAnyOrder(
                "sessionId", "interviewId", "userId", "status", "questions");
        assertThat(json.get("status").asString()).isEqualTo("IN_PROGRESS");

        JsonNode question = json.get("questions").get(0);
        assertThat(question.propertyNames()).containsExactlyInAnyOrder(
                "id", "category", "question", "personaId");
        assertThat(question.get("id").asLong()).isEqualTo(1L);
        assertThat(question.get("category").asString()).isEqualTo("선택 근거와 대안 비교");
        assertThat(question.get("question").asString()).isEqualTo("왜 Redis 를 썼나요?");
        assertThat(question.get("personaId").asLong()).isEqualTo(9L);
    }
}
