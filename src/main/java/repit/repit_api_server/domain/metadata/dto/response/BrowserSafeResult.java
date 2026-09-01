package repit.repit_api_server.domain.metadata.dto.response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 브라우저로 나가는 분석 결과에서 채점 기준을 걷어낸다.
 *
 * <p>{@code result.interview[]}에는 질문마다 기대 답변이 붙어 있다. 그 값은 채점의 유일한
 * 기준이라 API에서 채팅 서버로만 가야 하고, 면접 전에 브라우저로 내려가면 지원자가 거기 맞춰
 * 답을 준비할 수 있다. 준비 조회에서만 막아서는 소용이 없다 — 분석 결과 조회와 SSE로 같은
 * 값이 그대로 나가기 때문이다.
 *
 * <p>결과는 분석 서버가 준 모양 그대로 jsonb에 담겨 있어 우리가 형태를 못 박아둘 수 없다.
 * 그래서 아는 자리만 걷어내고 나머지는 손대지 않는다. 모양이 달라지면 걷어내지 못하는 대신
 * 조회가 깨지지는 않는다.
 *
 * <p>읽어온 값을 그대로 고치지 않고 베껴서 고친다. 결과는 영속성 컨텍스트가 들고 있는 엔티티의
 * 것이라, 손대면 걷어낸 상태가 DB로 되돌아 저장돼 면접에 쓸 채점 기준이 사라진다.
 */
public final class BrowserSafeResult {

    private static final String INTERVIEW = "interview";
    // 저장된 표기가 한 가지로 유지된다는 보장이 없어 두 표기를 모두 걷어낸다.
    private static final List<String> EXPECTED_ANSWER_KEYS = List.of("expected_answer", "expectedAnswer");

    private BrowserSafeResult() {
    }

    public static Object withoutExpectedAnswers(Object result) {
        if (!(result instanceof Map<?, ?> map)) {
            return result;
        }
        if (!(map.get(INTERVIEW) instanceof List<?> interview)) {
            return result;
        }

        List<Object> questions = new ArrayList<>(interview.size());
        for (Object question : interview) {
            questions.add(withoutExpectedAnswer(question));
        }

        Map<Object, Object> copy = new LinkedHashMap<>(map);
        copy.put(INTERVIEW, questions);
        return copy;
    }

    private static Object withoutExpectedAnswer(Object question) {
        if (!(question instanceof Map<?, ?> map)) {
            return question;
        }
        Map<Object, Object> copy = new LinkedHashMap<>(map);
        EXPECTED_ANSWER_KEYS.forEach(copy::remove);
        return copy;
    }
}
