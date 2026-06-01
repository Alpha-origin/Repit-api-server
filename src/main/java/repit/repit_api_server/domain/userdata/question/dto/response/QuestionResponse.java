package repit.repit_api_server.domain.userdata.question.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import repit.repit_api_server.domain.userdata.interview.entity.Interview;
import repit.repit_api_server.domain.userdata.question.entity.Question;
import repit.repit_api_server.domain.userdata.question.entity.enums.Type;

import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionResponse {
    private Long id;
    private Interview interview;
    private Question question;
    private Type type;
    private String intention;
    private String content;
    private LocalDateTime createdAt;

    public QuestionResponse from(Question question) {
        return new QuestionResponse(
                getId(),
                getInterview(),
                getQuestion(),
                getType(),
                getIntention(),
                getContent(),
                getCreatedAt()
        );
    }
}
