package repit.repit_api_server.domain.userdata.question.dto.request;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import repit.repit_api_server.domain.userdata.interview.entity.Interview;
import repit.repit_api_server.domain.userdata.question.entity.Question;
import repit.repit_api_server.domain.userdata.question.entity.enums.Type;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class QuestionRequest {

    private Interview interview;

    private Question question;

    private Type type;

    private String intention;

    private String content;

}
