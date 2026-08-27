package repit.repit_api_server.domain.userdata.answer.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AnswerRequest {
    private Long interviewId;

    private Long questionId;

    // 비어 오면 비어 있는 채로 저장한다. int 로 받으면 보내지 않은 것과 0초가 같아져,
    // 저장된 뒤에는 둘을 구분할 방법이 없다.
    private Integer responseTime;

    private String content;
}
