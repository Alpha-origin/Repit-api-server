package repit.repit_api_server.domain.userdata.feedback.dto.response;

import java.util.List;

/**
 * 문항 하나에서 점수에 들어간 역량 하나(competency-v1). 주 역량이 앞에 온다.
 *
 * <p>등급은 분석 서버가 근거 인용 조합으로 계산하고 응답 적중 상한까지 적용한 최종값이다. 인용은 사용자 답변(꼬리질문이면
 * 부모 답변 포함)의 원문 조각이라, 화면은 그대로 "이 문장 때문에"로 보여 준다. 콜백을 저장할 때 모양이 어긋난 항목과
 * 인용은 이미 버렸다({@code CompetencyReader}).
 *
 * @param competency PROBLEM_SOLVING / KNOWLEDGE / OWNERSHIP / COLLABORATION / GROWTH / MOTIVATION.
 *                   MOTIVATION은 종합 점수에 들어가지 않는 별도 지표다.
 * @param role       PRIMARY(문항이 겨냥한 역량, 항상 채점) / SECONDARY(근거가 있어서 더한 역량)
 * @param level      0..4
 * @param score      0..100 (= level × 25)
 */
public record CompetencyScoreResponse(String competency, String role, Integer level, Integer score,
                                      List<Evidence> evidence) {

    public CompetencyScoreResponse {
        evidence = evidence == null ? List.of() : evidence;
    }

    /** @param slot 근거 종류(reason, mechanism 등). 화면에는 쓰지 않는다. */
    public record Evidence(String slot, String quote) {
    }
}
