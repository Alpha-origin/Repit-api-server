package repit.repit_api_server.domain.userdata.feedback.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import repit.repit_api_server.domain.userdata.question.entity.enums.Type;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 분석 서버 POST /feedback/multi 요청 본문.
 *
 * <p>1:1과 달리 면접관이 여럿이라, 질문마다 누가 물었는지와 참여 면접관 명단이 함께 나간다.
 * 명단이 있어야 담당 문항이 없는 면접관도 결과에 자리를 얻는다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedbackMultiRequest {
    private String sessionId;
    private String interviewId;
    private String userId;
    // 면접에 참여한 면접관 전원. 1~6명.
    private List<Persona> personas;
    // 진행 순서대로. ORIGINAL + FOLLOW를 모두 담는다.
    private List<Question> questions;
    private List<Answer> answers;
    private String callbackUrl;

    /** role(직책)이 채점 관점을, style(성향)이 짚을 대상을, tone(어조)이 피드백 어조를 정한다. */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Persona {
        private String personaId;
        private String role;
        private String style;
        private String tone;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Question {
        private String questionId;
        // 이 질문을 한 면접관. 비면 분석 서버가 요청 전체를 422로 거부한다.
        private String personaId;
        // ORIGINAL이면 반드시 null, FOLLOW면 반드시 값이 있어야 한다. 어기면 422다.
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
