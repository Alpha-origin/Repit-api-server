package repit.repit_api_server.domain.userdata.interview.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import repit.repit_api_server.domain.userdata.interview.dto.request.SaveInterviewRequest;
import repit.repit_api_server.domain.userdata.interview.dto.request.SendUserDataRequest;
import repit.repit_api_server.domain.userdata.interview.dto.response.InterviewResponse;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.PersonaEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.question.entity.QuestionEntity;
import repit.repit_api_server.domain.userdata.question.repository.AnswerRepository;
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
    private final AnswerRepository answerRepository;

    public InterviewResponse createInterview(String authorization, PersonaEntity persona) {
        UserResponse user = authServerClient.getUser(authorization);
        String sessionId = UUID.randomUUID().toString();

        InterviewEntity interview = InterviewEntity.builder()
                .userId(user.getId())
                .persona(persona)
                .status(Status.IN_PROGRESS)
                .sessionId(sessionId)
                .build();

        InterviewEntity saved = interviewRepository.save(interview);
        return InterviewResponse.from(saved);
    }

    public void sendUserData(String authorization, Long interviewId) {
        InterviewEntity interview = interviewRepository.findById(interviewId).orElse(null);
        if (interview == null) {
            System.out.println("interview is null");
        }

        assert interview != null;
        String sessionId = interview.getSessionId();
        List<QuestionEntity> questions = new ArrayList<>(questionRepository.findAllByInterview(interview));
        SendUserDataRequest sendUserDataRequest = SendUserDataRequest.builder()
                .sessionId(sessionId)
                .questions(questions)
                .build();
        chatServerClient.sendUserData(authorization, sendUserDataRequest);

    }

    public List<InterviewResponse> getAllInterviewsByUserId(String authorization) {
        UserResponse user = authServerClient.getUser(authorization);

        return interviewRepository.findAllByUserId(user.getId());
    }

    public InterviewResponse getInterviewById(String authorization, Long interviewId) {
        InterviewEntity interview = interviewRepository.findById(interviewId).orElse(null);
        assert interview != null;
        return InterviewResponse.from(interview);
    }

    public void saveInterview(String authorization, SaveInterviewRequest request) {
        InterviewEntity interview = interviewRepository.findById(request.getInterviewId()).orElse(null);
        assert interview != null;
        interview.setSessionId(request.getSessionId());
        interview.setStatus(request.getStatus());
        interviewRepository.save(interview);

        answerRepository.saveAll(request.getAnswers());
        questionRepository.saveAll(request.getQuestions());
    }
}
