package repit.repit_api_server.domain.userdata.question.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 분석 서버 POST /questions/tailor/multi 요청 본문.
 *
 * <p>1:1 재작성과 달리 두 가지 일이 한 번에 일어난다 — 기술 면접관이 쓸 원질문을 다시 쓰고,
 * 나머지 면접관 몫의 질문을 새로 만든다. 그래서 원질문뿐 아니라 프로젝트 요약까지 실어 보내야 한다.
 * 분석 서버는 저장소가 없어 이 요청에 없는 값은 어디서도 구하지 못한다.
 *
 * <p>세션이 아직 없는 시점(면접 시작 직전)에 부르므로 매칭 키는 sessionId가 아니라 interviewId다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionTailorMultiRequest {
    private String interviewId;
    private String userId;
    // 재작성 개인화 축. 둘 다 선택이라 없으면 비워 보낸다.
    private String jobRole;
    private String experienceLevel;
    // 기술 면접관. questionCount는 아래 questions 개수와 반드시 같아야 한다.
    private Persona techPersona;
    // 비개발 면접관 1~4명. 직책이 겹치면 안 된다.
    private List<Persona> otherPersonas;
    // 기술 면접관이 쓸 원질문. /generate 산출물 중 이 서버가 고른 것이다.
    private List<Question> questions;
    // 신규 질문 생성의 근거. /generate 산출물을 그대로 넘긴다.
    private ProjectSummary projectSummary;
    private String callbackUrl;

    /** 면접관 한 명. role(직책)이 질문 관점을, style(말투)이 어조를 정한다. */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Persona {
        private String personaId;
        private String role;
        private String style;
        private Integer questionCount;
    }

    /** /generate 산출물(interview[]) 한 건. expectedAnswer가 채점 기준이 되므로 반드시 채운다. */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Question {
        private Integer id;
        private String category;
        private String question;
        private String expectedAnswer;
        private List<String> basedOn;
    }

    /**
     * /generate 산출물의 project_summary.
     *
     * <p>분석 서버가 받는 이름은 camelCase지만 /generate가 내려주는 원본은 snake_case다.
     * 저장은 원본 그대로 해두고 여기서 이름을 맞춰 옮긴다.
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProjectSummary {
        private String overview;
        private List<Repository> repositories;
        private List<CoreFeature> coreFeatures;
        private List<String> techStack;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Repository {
        private String repo;
        private String role;
        private String description;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CoreFeature {
        private String name;
        private String description;
        private List<String> basedOn;
    }
}
