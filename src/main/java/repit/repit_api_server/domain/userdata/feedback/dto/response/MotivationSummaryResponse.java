package repit.repit_api_server.domain.userdata.feedback.dto.response;

/**
 * 지원 동기·회사 이해(competency-v1). motivation 카테고리 문항만으로 매기고 종합 점수에 들어가지 않는다.
 * 축 하나와 같은 의미의 값이다({@link AxisScoreResponse}).
 *
 * @param score         0..100. 채점한 문항이 없으면 비어 있다
 * @param verdict       STRONG / SOLID / BORDERLINE / WEAK. 점수가 없으면 비어 있다
 * @param evidenceCount 이 점수에 들어간 문항 수
 */
public record MotivationSummaryResponse(Integer score, String verdict, Integer evidenceCount) {
}
