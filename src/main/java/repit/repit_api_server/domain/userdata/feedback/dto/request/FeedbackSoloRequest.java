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
    // API가 저장한 면접 상태. 분석 서버는 "COMPLETED"만 받고 나머지는 422로 거부한다.
    // 웹 입력을 옮기지 않고, 채점 직전에 검사한 DB 값을 그대로 싣는다.
    private String interviewStatus;
    private String sessionId;
    private String interviewId;
    private String userId;
    // 면접 시작 시점 기술 면접관의 직무와 직무명. 현재 사용자 프로필을 다시 읽지 않는다.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String jobRole;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String jobTitle;
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
        // 질문 카테고리(tech_choice 등). 분석 서버가 이 값으로 채점 역량을 고르고, 없거나 모르는 값이면 미분류로 채점한다.
        // 꼬리질문이 비어 있으면 분석 서버가 부모 값을 물려 쓴다. 저장된 값이 없으면 키 자체를 보내지 않는다.
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String category;
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
