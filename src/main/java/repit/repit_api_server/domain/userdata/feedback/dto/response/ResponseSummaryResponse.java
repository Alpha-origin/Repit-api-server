package repit.repit_api_server.domain.userdata.feedback.dto.response;

/**
 * 응답 적중 — 질문이 물은 것에 답했는지(competency-v1). 종합 점수에 들어가지 않는다.
 *
 * @param score       채점한 문항의 응답 등급 평균 × 25 (0..100)
 * @param hitCount    응답 등급이 3 이상인 문항 수. 화면의 "응답 적중 5/6문항"에서 5
 * @param gradedCount 채점한 문항 수
 */
public record ResponseSummaryResponse(Integer score, Integer hitCount, Integer gradedCount) {
}
