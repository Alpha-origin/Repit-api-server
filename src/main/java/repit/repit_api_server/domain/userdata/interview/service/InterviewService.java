package repit.repit_api_server.domain.userdata.interview.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import repit.repit_api_server.domain.userdata.interview.dto.request.PersonaRequest;
import repit.repit_api_server.domain.userdata.interview.dto.request.SaveInterviewRequest;
import repit.repit_api_server.domain.userdata.interview.dto.request.SendUserDataRequest;
import repit.repit_api_server.domain.userdata.interview.dto.response.InterviewResponse;
import repit.repit_api_server.domain.userdata.interview.dto.response.PersonaResponse;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.PersonaEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.interview.repository.PersonaRepository;
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
    private final PersonaRepository personaRepository;

    public InterviewResponse createInterview(String authorization, PersonaRequest request) throws RuntimeException {
        UserResponse user = authServerClient.getUser(authorization);
        if (user == null) {
            throw new RuntimeException("사용자를 찾을 수 없습니다");
        }
        String sessionId = UUID.randomUUID().toString();
        PersonaEntity persona = personaRepository.findByPersonaName(request.getPersonaName()).orElse(null);
        if (persona == null) {
            throw new RuntimeException("페르소나가 없습니다");
        }

        InterviewEntity interview = InterviewEntity.builder()
                .userId(user.getId())
                .personaId(persona.getPersonaId())
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
        PersonaEntity persona = personaRepository.findById(interview.getPersonaId()).orElse(null);
        if (persona == null) {
            System.out.println("persona is null");
        }

        assert persona != null;
        PersonaResponse personaResponse = PersonaResponse.from(persona);
        String sessionId = interview.getSessionId();
        List<QuestionEntity> questions = new ArrayList<>(questionRepository.findAllByInterviewId(interview.getInterviewId()));
        SendUserDataRequest sendUserDataRequest = SendUserDataRequest.builder()
                .sessionId(sessionId)
                .questions(questions)
                .persona(personaResponse)
                .build();
        chatServerClient.sendUserData(authorization, sendUserDataRequest);

    }

    public List<InterviewResponse> getAllInterviewsByUserId(String authorization) {
        UserResponse user = authServerClient.getUser(authorization);

        return interviewRepository.findAllByUserId(user.getId());
    }

    public InterviewResponse getInterviewById(Long interviewId) {
        InterviewEntity interview = interviewRepository.findById(interviewId).orElse(null);
        assert interview != null;
        return InterviewResponse.from(interview);
    }

    public void saveInterview(SaveInterviewRequest request) {
        InterviewEntity interview = interviewRepository.findById(request.getInterviewId()).orElse(null);
        assert interview != null;
        interview.setSessionId(request.getSessionId());
        interview.setStatus(request.getStatus());
        interviewRepository.save(interview);

        answerRepository.saveAll(answerRepository.findAllById(request.getAnswers()));
        questionRepository.saveAll(questionRepository.findAllById(request.getQuestions()));
    }
}
