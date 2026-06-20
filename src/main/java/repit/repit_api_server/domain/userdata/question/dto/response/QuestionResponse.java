package repit.repit_api_server.domain.userdata.question.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.question.entity.QuestionEntity;
import repit.repit_api_server.domain.userdata.question.entity.enums.Type;

import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionResponse {
    private Long questionId;
    private Long interviewId;
    private Long parentId;
    private Type type;
    private String intention;
    private String content;
    private LocalDateTime createdAt;

    public static QuestionResponse from(QuestionEntity question) {
        return new QuestionResponse(
                question.getQuestionId(),
                question.getInterviewId(),
                question.getParentId(),
                question.getType(),
                question.getIntention(),
                question.getContent(),
                question.getCreatedAt()

        );
    }
}
