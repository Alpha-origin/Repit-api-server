package repit.repit_api_server.domain.userdata.interview.dto.request;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.question.entity.AnswerEntity;
import repit.repit_api_server.domain.userdata.question.entity.QuestionEntity;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SaveInterviewRequest {
    private String sessionId;
    private Long interviewId;
    private Long userId;
    private Status status;
    private List<Long> questions;
    private List<Long> answers;
}
