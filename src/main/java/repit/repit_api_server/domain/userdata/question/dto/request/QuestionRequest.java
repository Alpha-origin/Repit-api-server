package repit.repit_api_server.domain.userdata.question.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.question.entity.QuestionEntity;
import repit.repit_api_server.domain.userdata.question.entity.enums.Type;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class QuestionRequest {

    private InterviewEntity interview;

    private QuestionEntity question;

    private Type type;

    private String intention;

    private String content;

}
