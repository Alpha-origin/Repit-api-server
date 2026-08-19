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

    private int responseTime;

    private String content;
}
