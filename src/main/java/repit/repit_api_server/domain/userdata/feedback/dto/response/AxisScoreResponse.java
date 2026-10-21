package repit.repit_api_server.domain.userdata.feedback.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 채점 축 하나의 점수와 가중치.
 *
 * <p>면접 전체가 해당 없는 축(axis-v4의 ACCURACY, competency-v1에서 확인되지 않은 역량)은 점수와 가중치가 모두
 * 비어 있다. 그때 나머지 가중치의 합은 100이 아니다.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AxisScoreResponse {
    // axis-v4: INTENT / DEPTH / SPECIFICITY / ACCURACY
    // competency-v1: PROBLEM_SOLVING / KNOWLEDGE / OWNERSHIP / COLLABORATION / GROWTH
    // 표시 이름은 클라이언트가 정한다.
    private String axis;
    private Integer score;
    // 실제 적용된 가중치(%). 비율(0.35)로 와도 %로 바꿔 둔다.
    private Integer weight;
    // STRONG / SOLID / BORDERLINE / WEAK. competency-v1부터. 점수가 없으면 비어 있다.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String verdict;
    // 이 축 점수에 들어간 문항 수. 1이면 화면에 "근거 부족"을 띄운다. competency-v1부터.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer evidenceCount;

    /** 판정과 근거 문항 수가 없는 axis-v4 축. */
    public AxisScoreResponse(String axis, Integer score, Integer weight) {
        this(axis, score, weight, null, null);
    }
}
