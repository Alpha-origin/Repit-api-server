package repit.repit_api_server.domain.userdata.answer.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import repit.repit_api_server.domain.userdata.answer.entity.AnswerEntity;

import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnswerResponse {
    private Long answerId;

    private Long interview;

    private Long question;

    private Long userId;

    private int responseTime;

    private String content;

    private LocalDateTime createdAt;

    public static AnswerResponse from(AnswerEntity answer) {
        return new AnswerResponse(
            answer.getAnswerId(),
            answer.getInterviewId(),
            answer.getQuestionId(),
            answer.getUserId(),
            answer.getResponseTime(),
            answer.getContent(),
            answer.getCreatedAt()
        );
    }
}
