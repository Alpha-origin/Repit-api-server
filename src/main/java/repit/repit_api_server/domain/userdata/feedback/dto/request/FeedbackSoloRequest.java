package repit.repit_api_server.domain.userdata.feedback.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import repit.repit_api_server.domain.userdata.question.entity.enums.Type;

import java.time.OffsetDateTime;
import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedbackSoloRequest {
    private String sessionId;
    private String interviewId;
    private String userId;
    private String personaType;
    private String callbackUrl;
    private List<Question> questions;
    private List<Answer> answers;

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Question {
        private String questionId;
        // ORIGINAL이면 반드시 null, FOLLOW면 반드시 값이 있어야 한다. 어기면 분석 서버가 422로 거부한다.
        private String parentId;
        private Type type;
        private String intention;
        private String content;
        // ISO 8601 + UTC 오프셋(Z)으로 직렬화된다.
        private OffsetDateTime createdAt;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Answer {
        private String answerId;
        private String questionId;
        private String content;
        // ISO 8601 + UTC 오프셋(Z)으로 직렬화된다.
        private OffsetDateTime createdAt;
    }
}
