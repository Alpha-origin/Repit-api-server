package repit.repit_api_server.domain.userdata.interview.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import repit.repit_api_server.domain.userdata.interview.dto.response.InterviewResponse;
import repit.repit_api_server.domain.userdata.interview.entity.Interview;
import repit.repit_api_server.domain.userdata.interview.entity.Persona;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.global.client.AuthServerClient;
import repit.repit_api_server.global.common.ApiResponse;
import repit.repit_api_server.global.response.UserResponse;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InterviewService {

    private final InterviewRepository interviewRepository;
    private AuthServerClient authServerClient;

    public InterviewResponse saveInterview(String authorization, Persona persona) {
        UserResponse user = authServerClient.getUser(authorization);
        String sessionId = UUID.randomUUID().toString();

        Interview interview = Interview.builder()
                .userId(user.getId())
                .persona(persona)
                .status(Status.IN_PROGRESS)
                .sessionId(sessionId)
                .build();

        Interview saved = interviewRepository.save(interview);
        return InterviewResponse.from(saved);
    }
}
