package repit.repit_api_server.domain.userdata.feedback.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * 종합 점수가 어떻게 나왔는지. 축마다 점수와 실제 적용된 가중치가 실린다.
 *
 * <p>분석 서버는 문항마다 축 등급만 매기고 점수는 직접 계산한다(axis-v1). 그래서 표시된 축 점수와
 * 가중치로 {@code Σ score×weight / Σ weight}를 반올림하면 종합 점수와 같아야 한다.
 *
 * <p>콜백으로 받은 값을 그대로 jsonb에 두고 그대로 내보낸다. 방식이 바뀌면 축이 늘거나 이름이 바뀔 수
 * 있어 축 이름은 문자열로 둔다.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ScoreBreakdownResponse {
    // 채점 방식 버전(예: axis-v1). 버전이 다른 점수끼리는 같은 기준으로 비교하면 안 된다.
    private String scoringVersion;
    private List<AxisScoreResponse> axes;
    // 답변 사이의 일관성. 답변이 1개면 비어 있다. 종합 점수에는 들어가지 않는다.
    private Integer consistencyScore;
}
