package repit.repit_api_server.domain.metadata.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * /generate 성공 콜백의 result. jsonb로 통째 저장해둔 값을 질문 단위로 꺼내 쓸 때 사용한다.
 * project_summary는 N:1 질문 구성 요청에 그대로 실어 보내야 해서 함께 읽는다.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class GenerateResultResponse {
    private ProjectSummaryResponse project_summary;
    private List<GeneratedQuestionResponse> interview;
}
