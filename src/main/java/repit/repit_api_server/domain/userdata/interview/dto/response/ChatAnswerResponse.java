package repit.repit_api_server.domain.userdata.interview.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ChatAnswerResponse {
    private Long answerId;
    private Long interviewId;
    private Long questionId;
    private Long userId;
    private int responseTime;
    private String answerContent;
    private OffsetDateTime answerCreatedAt;
}
