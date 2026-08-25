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
    private Integer score;
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
                .comment(persona.getComment())
                .strengths(persona.getStrengths())
                .improvements(persona.getImprovements())
                .answeredCount(persona.getAnsweredCount())
                .questionCount(persona.getQuestionCount())
                .build();
    }
}
