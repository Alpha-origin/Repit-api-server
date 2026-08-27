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

    // 엔티티와 같은 Integer 다. int 로 받으면 응답 시간이 비어 있는 답변에서 언박싱하다
    // NPE 가 나고, 답변 목록 전체가 500 이 된다.
    private Integer responseTime;

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
