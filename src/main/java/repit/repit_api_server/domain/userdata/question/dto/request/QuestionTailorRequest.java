package repit.repit_api_server.domain.userdata.question.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 분석 서버 POST /questions/tailor 요청 본문. 호출자가 Java라 camelCase로 나간다.
 * 세션이 아직 없는 시점(면접 시작 직전)에 부르므로 매칭 키는 sessionId가 아니라 interviewId다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionTailorRequest {
    private String interviewId;
    private String userId;
    private Profile profile;
    private List<Question> questions;
    private String callbackUrl;

    /** 세 축 모두 선택이지만 하나도 없으면 분석 서버가 422로 거부한다. */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Profile {
        private String jobRole;
        private String experienceLevel;
        private String personaType;
        // 면접관 어조. 성향(personaType)과 독립된 축이다.
        private String personaTone;
    }

    /** /generate 산출물(interview[])을 그대로 되돌려주는 형태. 1~10개, id 중복 불가. */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Question {
        private Integer id;
        private String category;
        private String question;
        // 재작성 대상이 아니라 재작성 후에도 유지해야 할 검증 포인트다.
        private String expectedAnswer;
        private List<String> basedOn;
    }
}
