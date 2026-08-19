package repit.repit_api_server.domain.userdata.interview.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import repit.repit_api_server.domain.userdata.question.entity.enums.Type;

import java.time.OffsetDateTime;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ChatQuestionResponse {
    private Long questionId;
    private Long parentId;
    private Type questionType;
    private String questionIntention;
    private String questionContent;
    private OffsetDateTime questionCreatedAt;
}
