package repit.repit_api_server.domain.userdata.feedback.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackPersonaEntity;

import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedbackPersonaResponse {
    private Long personaId;
    private String personaRole;
    // 담당 답변이 없는 면접관은 비어 있다. 0점과 구분된다.
    private Integer score;
    // 이 면접관 점수의 산출 근거. 종합과 같은 구조다.
    private ScoreBreakdownResponse scoreBreakdown;
    private String comment;
    private List<String> strengths;
    private List<String> improvements;
    private Integer answeredCount;
    private Integer questionCount;

    public static FeedbackPersonaResponse from(FeedbackPersonaEntity persona) {
        return FeedbackPersonaResponse.builder()
                .personaId(persona.getPersonaId())
                .personaRole(persona.getPersonaRole())
                .score(persona.getScore())
                .scoreBreakdown(persona.getScoreBreakdown())
                .comment(persona.getComment())
                .strengths(persona.getStrengths())
                .improvements(persona.getImprovements())
                .answeredCount(persona.getAnsweredCount())
                .questionCount(persona.getQuestionCount())
                .build();
    }
}
