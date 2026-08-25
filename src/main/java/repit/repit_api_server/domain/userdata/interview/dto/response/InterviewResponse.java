package repit.repit_api_server.domain.userdata.interview.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;

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
    private String sessionId;
    private Status status;
    private LocalDateTime createdAt;

    public static InterviewResponse from(InterviewEntity interview) {
        return from(interview, List.of());
    }

    public static InterviewResponse from(InterviewEntity interview, List<Long> personaIds) {
        return new InterviewResponse(
                interview.getInterviewId(),
                interview.getUserId(),
                interview.getMode(),
                interview.getPersonaId(),
                personaIds,
                interview.getSessionId(),
                interview.getStatus(),
                interview.getCreatedAt()
        );
    }
}
