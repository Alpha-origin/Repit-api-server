package repit.repit_api_server.domain.userdata.question.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.question.entity.AnswerEntity;
import repit.repit_api_server.domain.userdata.question.entity.QuestionEntity;

import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnswerResponse {
    private Long id;

    private InterviewEntity interview;

    private QuestionEntity question;

    private Long userId;

    private int responseTime;

    private String content;

    private LocalDateTime createdAt;

    public static AnswerResponse from(AnswerEntity answer) {
        return new AnswerResponse(
            answer.getAnswerId(),
            answer.getInterview(),
            answer.getQuestion(),
            answer.getUserId(),
            answer.getResponseTime(),
            answer.getContent(),
            answer.getCreatedAt()
        );
    }
}
