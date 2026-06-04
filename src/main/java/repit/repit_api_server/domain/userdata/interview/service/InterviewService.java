package repit.repit_api_server.domain.userdata.interview.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import repit.repit_api_server.domain.userdata.interview.dto.request.SendUserDataRequest;
import repit.repit_api_server.domain.userdata.interview.dto.response.InterviewResponse;
import repit.repit_api_server.domain.userdata.interview.entity.Interview;
import repit.repit_api_server.domain.userdata.interview.entity.Persona;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.question.entity.Question;
import repit.repit_api_server.domain.userdata.question.repository.QuestionRepository;
import repit.repit_api_server.global.client.AuthServerClient;
import repit.repit_api_server.global.client.ChatServerClient;
import repit.repit_api_server.global.response.UserResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InterviewService {

    private final InterviewRepository interviewRepository;
    private final QuestionRepository questionRepository;
    private final ChatServerClient chatServerClient;
    private final AuthServerClient authServerClient;

    public InterviewResponse createInterview(String authorization, Persona persona) {
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

    public void sendUserData(String authorization, Long interviewId) {
        Interview interview = interviewRepository.findById(interviewId).orElse(null);

        if (interview != null) {
            String sessionId = interview.getSessionId();
            List<Question> questions = new ArrayList<>(questionRepository.findAllByInterview(interview));
            SendUserDataRequest sendUserDataRequest = SendUserDataRequest.builder()
                    .sessionId(sessionId)
                    .questions(questions)
                    .build();
            chatServerClient.sendUserData(authorization, sendUserDataRequest);
        }
    }

    public List<InterviewResponse> getAllInterviewsByUserId(String authorization) {
        UserResponse user = authServerClient.getUser(authorization);

        return interviewRepository.findAllByUserId(user.getId());
    }
}
