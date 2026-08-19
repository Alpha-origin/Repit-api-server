package repit.repit_api_server.domain.userdata.question.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * 면접에 실제로 사용할 질문 한 건.
 * 분석 서버는 재작성된 본문만 돌려주므로 나머지 필드는 원질문 값을 그대로 유지한다.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TailoredQuestionResponse {
    private Integer id;
    private String category;
    private String question;
    private String expectedAnswer;
    private List<String> basedOn;
}
