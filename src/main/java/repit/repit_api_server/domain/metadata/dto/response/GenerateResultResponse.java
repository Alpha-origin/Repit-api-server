package repit.repit_api_server.domain.metadata.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * /generate 성공 콜백의 result. jsonb로 통째 저장해둔 값을 질문 단위로 꺼내 쓸 때 사용한다.
 * project_summary는 이 서버가 해석할 일이 없어 원형 그대로 둔다.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class GenerateResultResponse {
    private Object project_summary;
    private List<GeneratedQuestionResponse> interview;
}
