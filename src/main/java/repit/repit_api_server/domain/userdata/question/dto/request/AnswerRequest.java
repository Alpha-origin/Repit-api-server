package repit.repit_api_server.domain.userdata.question.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.question.entity.QuestionEntity;

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
