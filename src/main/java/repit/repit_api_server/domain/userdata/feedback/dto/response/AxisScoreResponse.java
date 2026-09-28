package repit.repit_api_server.domain.userdata.feedback.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 채점 축 하나의 점수와 가중치.
 *
 * <p>면접 전체가 해당 없는 축(주로 ACCURACY)은 점수와 가중치가 모두 비어 있다. 그때 나머지 가중치의
 * 합은 100이 아니다.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AxisScoreResponse {
    // INTENT / DEPTH / SPECIFICITY / ACCURACY. 표시 이름은 클라이언트가 정한다.
    private String axis;
    private Integer score;
    // 실제 적용된 가중치(%). 비율(0.35)로 와도 %로 바꿔 둔다.
    private Integer weight;
}
