package repit.repit_api_server.domain.userdata.interview.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import repit.repit_api_server.domain.company.dto.CompanyBadge;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.interview.entity.enums.AnswerMode;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.persona.entity.PersonaEntity;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class InterviewResponse {
    private Long interviewId;
    private Long userId;
    private InterviewMode mode;
    // 1:1이면 면접관 하나, N:1이면 비어 있고 personaIds가 채워진다.
    private Long personaId;
    // N:1 면접관 목록. 진행 순서대로다.
    private List<Long> personaIds;
    // 대표 면접관. 1:1은 그 면접관, N:1은 진행 순서 맨 앞 면접관이다. 면접관이 없으면 비어 있다.
    private InterviewPersonaSummary persona;
    private String sessionId;
    private Status status;
    private LocalDateTime createdAt;
    // 면접을 만들 때 고른 기업. 고르지 않았으면 비어 있다.
    private CompanyBadge company;
    private AnswerMode answerMode;

    /**
     * @param personaIds N:1 면접관 목록. 1:1은 빈 목록이다.
     * @param persona    대표 면접관. 없으면 null이다.
     */
    public static InterviewResponse from(InterviewEntity interview, List<Long> personaIds, PersonaEntity persona) {
        return new InterviewResponse(
                interview.getInterviewId(),
                interview.getUserId(),
                interview.getMode(),
                interview.getPersonaId(),
                personaIds,
                InterviewPersonaSummary.of(persona),
                interview.getSessionId(),
                interview.getStatus(),
                interview.getCreatedAt(),
                CompanyBadge.of(interview.getCompanyId(), interview.getCompanySnapshot()),
                interview.getAnswerMode()
        );
    }
}
