package repit.repit_api_server.domain.userdata.interview.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ChatInterviewResponse {
    private String sessionId;
    private Long interviewId;
    private Long userId;
    private Long personaId;
    private Status status;
    private int currentQuestionIndex;
}
