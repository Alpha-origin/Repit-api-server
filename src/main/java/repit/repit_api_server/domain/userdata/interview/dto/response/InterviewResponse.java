package repit.repit_api_server.domain.userdata.interview.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.PersonaEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;

import java.time.LocalDateTime;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class InterviewResponse {
    private Long interviewId;
    private Long userId;
    private Long personaId;
    private String sessionId;
    private Status status;
    private LocalDateTime createdAt;

    public static InterviewResponse from(InterviewEntity interview) {
        return new InterviewResponse(
                interview.getInterviewId(),
                interview.getUserId(),
                interview.getPersonaId(),
                interview.getSessionId(),
                interview.getStatus(),
                interview.getCreatedAt()
        );
    }
}
