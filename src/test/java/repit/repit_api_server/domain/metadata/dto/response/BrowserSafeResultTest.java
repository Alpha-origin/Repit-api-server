package repit.repit_api_server.domain.metadata.dto.response;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 브라우저로 나가는 분석 결과에서 채점 기준을 걷어낸다.
 *
 * <p>준비 조회에서만 막으면 소용이 없다. 같은 값이 분석 결과 조회와 SSE로 그대로 나간다.
 */
class BrowserSafeResultTest {

    private Map<String, Object> question(String expectedAnswerKey) {
        Map<String, Object> question = new LinkedHashMap<>();
        question.put("id", 1);
        question.put("category", "tech_choice");
        question.put("question", "왜 Redis 를 썼나요?");
        question.put(expectedAnswerKey, "캐시 계층 선택 근거와 대안 비교");
        question.put("based_on", List.of("order-api/CacheConfig.java"));
        return question;
    }

    private Map<String, Object> result(String expectedAnswerKey) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("project_summary", Map.of("overview", "주문 처리를 맡는 백엔드"));
        result.put("interview", List.of(question(expectedAnswerKey)));
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstQuestion(Object masked) {
        return (Map<String, Object>) ((List<Object>) ((Map<String, Object>) masked).get("interview")).getFirst();
    }

    /** 저장된 표기가 한 가지로 유지된다는 보장이 없어 두 표기를 모두 걷어내야 한다. */
    @Test
    void 두_표기_모두_걷어낸다() {
        assertThat(firstQuestion(BrowserSafeResult.withoutExpectedAnswers(result("expected_answer"))))
                .doesNotContainKey("expected_answer");
        assertThat(firstQuestion(BrowserSafeResult.withoutExpectedAnswers(result("expectedAnswer"))))
                .doesNotContainKey("expectedAnswer");
    }

    /** 질문 본문과 근거는 그대로 나가야 한다. 웹이 준비된 질문을 보여주는 근거다. */
    @Test
    void 나머지_값은_그대로_둔다() {
        Map<String, Object> masked = firstQuestion(BrowserSafeResult.withoutExpectedAnswers(result("expected_answer")));

        assertThat(masked).containsEntry("id", 1);
        assertThat(masked).containsEntry("question", "왜 Redis 를 썼나요?");
        assertThat(masked).containsKey("based_on");
        // 질문 밖의 값도 그대로다. N:1 질문의 근거가 되는 프로젝트 요약이 여기 있다.
        Map<?, ?> maskedResult = (Map<?, ?>) BrowserSafeResult.withoutExpectedAnswers(result("expected_answer"));
        assertThat(maskedResult.containsKey("project_summary")).isTrue();
    }

    /**
     * 읽어온 값을 그대로 고치면, 걷어낸 상태가 엔티티를 타고 DB로 되돌아 저장돼 면접에 쓸
     * 채점 기준이 사라진다.
     */
    @Test
    void 원본은_건드리지_않는다() {
        Map<String, Object> original = result("expected_answer");

        BrowserSafeResult.withoutExpectedAnswers(original);

        assertThat(firstQuestion(original)).containsKey("expected_answer");
    }

    /** 결과 모양은 분석 서버가 정한다. 아는 자리가 없으면 걷어내지 못할 뿐, 조회가 깨져선 안 된다. */
    @Test
    void 모르는_모양은_그대로_돌려준다() {
        assertThat(BrowserSafeResult.withoutExpectedAnswers(null)).isNull();
        assertThat(BrowserSafeResult.withoutExpectedAnswers("요약이 문자열로 들어 있다"))
                .isEqualTo("요약이 문자열로 들어 있다");
        assertThat(BrowserSafeResult.withoutExpectedAnswers(Map.of("project_summary", "요약")))
                .isEqualTo(Map.of("project_summary", "요약"));
    }
}
