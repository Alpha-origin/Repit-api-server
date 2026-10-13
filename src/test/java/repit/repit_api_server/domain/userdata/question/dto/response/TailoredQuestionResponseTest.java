package repit.repit_api_server.domain.userdata.question.dto.response;

import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** question_tailor의 JSONB 컬럼에 담기는 질문. 발음 문장이 생기기 전에 저장된 행도 그대로 읽혀야 한다. */
class TailoredQuestionResponseTest {

    @Test
    void 발음_문장이_없는_기존_행도_읽힌다() {
        String stored = """
                [{"id":1,"personaId":null,"category":"tech_choice","question":"왜 Redis 를 썼나요?",
                  "intention":"선택 근거","expectedAnswer":"대안 비교","basedOn":["order-api/src/cache.py"]}]
                """;

        List<TailoredQuestionResponse> questions =
                new ObjectMapper().readValue(stored, new TypeReference<List<TailoredQuestionResponse>>() {});

        assertThat(questions).singleElement().satisfies(question -> {
            assertThat(question.getQuestion()).isEqualTo("왜 Redis 를 썼나요?");
            assertThat(question.getSpokenText()).isNull();
        });
    }
}
