package repit.repit_api_server.domain.userdata.interview.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import repit.repit_api_server.domain.metadata.entity.AnalysisDataEntity;
import repit.repit_api_server.domain.metadata.repository.AnalysisDataRepository;
import repit.repit_api_server.domain.userdata.persona.dto.request.PersonaRequest;
import repit.repit_api_server.domain.userdata.interview.dto.request.ChatInterviewPrepareRequest;
import repit.repit_api_server.domain.userdata.interview.dto.request.SaveInterviewRequest;
import repit.repit_api_server.domain.userdata.interview.dto.response.ChatInterviewAllResponse;
import repit.repit_api_server.domain.userdata.interview.dto.response.ChatInterviewResponse;
import repit.repit_api_server.domain.userdata.interview.dto.response.InterviewResponse;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.persona.entity.PersonaEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.persona.repository.PersonaRepository;
import repit.repit_api_server.domain.userdata.answer.repository.AnswerRepository;
import repit.repit_api_server.domain.userdata.question.repository.QuestionRepository;
import repit.repit_api_server.global.client.AuthServerClient;
import repit.repit_api_server.global.client.ChatServerClient;
import repit.repit_api_server.global.response.UserResponse;

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
    private final AnalysisDataRepository analysisDataRepository;

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

    public ChatInterviewResponse prepareInterview(String authorization, Long interviewId) {
        InterviewEntity interview = interviewRepository.findById(interviewId)
                .orElseThrow(() -> new RuntimeException("면접을 찾을 수 없습니다"));
        PersonaEntity persona = personaRepository.findById(interview.getPersonaId())
                .orElseThrow(() -> new RuntimeException("페르소나가 없습니다"));

        ChatInterviewPrepareRequest chatRequest = ChatInterviewPrepareRequest.builder()
                .sessionId(interview.getSessionId())
                .interviewId(interview.getInterviewId())
                .userId(interview.getUserId())
                .personaId(persona.getPersonaId())
                .personaType(persona.getType())
                .jobId(resolveJobId(interview.getUserId()))
                .build();

        return chatServerClient.prepareInterview(authorization, chatRequest);
    }

    // 해당 사용자의 가장 최근 분석 결과를 면접에 사용한다.
    private UUID resolveJobId(Long userId) {
        String jobId = analysisDataRepository
                .findTopByUserIdAndResultIsNotNullOrderByCreatedAtDesc(userId)
                .map(AnalysisDataEntity::getJobId)
                .orElseThrow(() -> new RuntimeException("완료된 분석 결과가 없습니다. 포트폴리오 분석을 먼저 진행해주세요."));
        try {
            return UUID.fromString(jobId);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("jobId 형식이 올바르지 않습니다: " + jobId);
        }
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

    public ChatInterviewAllResponse getChatInterview(Long interviewId) {
        InterviewEntity interview = interviewRepository.findById(interviewId)
                .orElseThrow(() -> new RuntimeException("면접을 찾을 수 없습니다"));
        return chatServerClient.getInterview(interview.getSessionId());
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
