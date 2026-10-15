package repit.repit_api_server.domain.userdata.feedback.dto.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import repit.repit_api_server.domain.company.dto.CompanySnapshot;
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
    // 면접관 어조. 성향(personaType)과 독립된 축이다.
    private String personaTone;
    private String callbackUrl;
    private List<Question> questions;
    private List<Answer> answers;
    // 면접을 만들 때 고른 기업. 채점 요청이 아니라 기업 관점 코멘트(companyFit)를 만드는 데만 쓴다.
    // 고르지 않은 면접은 키 자체를 보내지 않는다 — null도 보내지 않는다.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private CompanySnapshot company;

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
