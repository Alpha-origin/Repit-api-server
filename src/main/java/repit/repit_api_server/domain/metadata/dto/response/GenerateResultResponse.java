package repit.repit_api_server.domain.metadata.dto.response;

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
    private Object project_summary;
    private List<GeneratedQuestionResponse> interview;
}
