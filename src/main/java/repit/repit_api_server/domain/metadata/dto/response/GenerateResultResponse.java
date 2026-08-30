package repit.repit_api_server.domain.metadata.dto.response;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * /generate 성공 콜백의 result. jsonb로 통째 저장해둔 값을 질문 단위로 꺼내 쓸 때 사용한다.
 *
 * <p>project_summary는 원형 그대로 둔다. N:1 질문 구성에서만 해석하는 값인데, 여기서 형태를 못
 * 박으면 실제 모양이 조금만 달라도 이 클래스를 읽는 것 자체가 실패한다. 그 경로는 1:1 면접
 * 시작도 함께 지나므로, N:1에만 필요한 해석 때문에 1:1까지 멈춰서는 안 된다.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class GenerateResultResponse {

    /**
     * 이름이 어긋나면 값이 조용히 빈다. 그러면 N:1은 "요약이 없다"며 열리지 않는데, 정작 분석
     * 결과에는 요약이 들어 있어 원인을 찾을 실마리가 남지 않는다. 요약 안쪽 필드가 두 표기를
     * 모두 받아두는 것과 같은 이유로, 요약을 꺼내는 이 열쇠도 두 표기를 다 받는다.
     */
    @JsonAlias("projectSummary")
    private Object project_summary;

    private List<GeneratedQuestionResponse> interview;
}
