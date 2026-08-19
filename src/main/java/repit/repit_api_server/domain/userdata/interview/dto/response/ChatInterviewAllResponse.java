package repit.repit_api_server.domain.userdata.interview.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Type;

import java.time.OffsetDateTime;
import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ChatInterviewAllResponse {
    private String sessionId;
    private Long interviewId;
    private Long userId;
    private Long personaId;
    private Type personaType;
    private Status status;
    private int currentQuestionIndex;
    private OffsetDateTime createdAt;
    private List<ChatInterviewQnAResponse> qnAResponses;
}
