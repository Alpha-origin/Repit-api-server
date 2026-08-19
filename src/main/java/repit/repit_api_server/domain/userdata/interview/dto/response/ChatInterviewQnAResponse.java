package repit.repit_api_server.domain.userdata.interview.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ChatInterviewQnAResponse {
    private ChatQuestionResponse question;
    private ChatAnswerResponse answer;
}
