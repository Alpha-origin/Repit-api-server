package repit.repit_api_server.domain.userdata.question.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * 면접에 실제로 사용할 질문 한 건.
 * 1:1 재작성에서 분석 서버는 본문만 돌려주므로 나머지 필드는 원질문 값을 그대로 유지한다.
 * N:1 구성에서는 전체가 새로 오고, 질문마다 맡은 면접관이 붙는다.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TailoredQuestionResponse {
    private Integer id;
    // 이 질문을 맡은 면접관. 1:1은 면접관이 하나뿐이라 비어 있다.
    private Long personaId;
    private String category;
    private String question;
    private String expectedAnswer;
    private List<String> basedOn;
}
