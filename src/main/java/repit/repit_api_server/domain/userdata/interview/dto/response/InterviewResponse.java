package repit.repit_api_server.domain.userdata.interview.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import repit.repit_api_server.domain.userdata.interview.entity.Persona;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;

import java.time.LocalDateTime;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class InterviewResponse {
    private Long id;
    private Long userId;
    private Persona persona;
    private String sessionId;
    private Status status;
    private LocalDateTime createdAt;
}
