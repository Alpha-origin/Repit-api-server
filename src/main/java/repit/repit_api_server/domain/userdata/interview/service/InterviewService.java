package repit.repit_api_server.domain.userdata.interview.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import repit.repit_api_server.domain.userdata.interview.dto.request.CreateInterviewRequest;
import repit.repit_api_server.domain.userdata.interview.dto.request.SaveInterviewRequest;
import repit.repit_api_server.domain.userdata.interview.dto.response.ChatInterviewAllResponse;
import repit.repit_api_server.domain.userdata.interview.dto.response.InterviewPrepareResponse;
import repit.repit_api_server.domain.userdata.interview.dto.response.InterviewResponse;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.persona.entity.PersonaEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.persona.repository.PersonaRepository;
import repit.repit_api_server.domain.userdata.answer.repository.AnswerRepository;
import repit.repit_api_server.domain.userdata.question.entity.QuestionTailorEntity;
import repit.repit_api_server.domain.userdata.question.entity.enums.TailorStatus;
import repit.repit_api_server.domain.userdata.question.repository.QuestionRepository;
import repit.repit_api_server.domain.userdata.question.service.QuestionTailorService;
import repit.repit_api_server.global.client.AuthServerClient;
import repit.repit_api_server.global.client.ChatServerClient;
import repit.repit_api_server.global.exception.BusinessException;
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
    private final QuestionTailorService questionTailorService;

    public InterviewResponse createInterview(String authorization, CreateInterviewRequest request) {
        UserResponse user = currentUser(authorization);
        PersonaEntity persona = findPersona(request);

        InterviewEntity interview = InterviewEntity.builder()
                .userId(user.getId())
                .personaId(persona.getPersonaId())
                .status(Status.IN_PROGRESS)
                .sessionId(UUID.randomUUID().toString())
                .build();

        InterviewEntity saved = interviewRepository.save(interview);
        return InterviewResponse.from(saved);
    }

    /**
     * 면접이 고르는 것은 페르소나 하나다.
     * id가 오면 그것을 쓰고, 없으면 이름으로 찾는다 — 이름은 바뀔 수 있어 id 쪽이 안전하지만,
     * 이름으로 보내던 기존 웹 요청도 그대로 받아야 한다.
     */
    private PersonaEntity findPersona(CreateInterviewRequest request) {
        if (request.getPersonaId() != null) {
            return personaRepository.findById(request.getPersonaId())
                    .orElseThrow(() -> BusinessException.notFound("페르소나를 찾을 수 없습니다"));
        }
        if (request.getPersonaName() == null || request.getPersonaName().isBlank()) {
            throw BusinessException.unprocessable("면접에 쓸 페르소나를 지정해주세요.");
        }
        return personaRepository.findByPersonaName(request.getPersonaName())
                .orElseThrow(() -> BusinessException.notFound(
                        "페르소나를 찾을 수 없습니다: " + request.getPersonaName()));
    }

    private UserResponse currentUser(String authorization) {
        UserResponse user = authServerClient.getUser(authorization);
        if (user == null || user.getId() == null) {
            throw BusinessException.unauthorized("사용자 정보를 확인할 수 없습니다. 다시 로그인해주세요.");
        }
        return user;
    }

    /**
     * 면접 시작. 웹이 부르는 진입점이다.
     *
     * <p>DB에 저장돼 있는 원질문을 페르소나와 함께 분석 서버로 보내 재작성을 요청한다.
     * 재작성은 비동기라 여기서는 접수만 하고, 콜백이 도착해 질문이 확정되면 그때
     * 채팅 서버로 면접 데이터가 넘어간다. 준비 상태는 GET /api/questions/tailor 로 확인한다.
     */
    public InterviewPrepareResponse prepareInterview(String authorization, Long interviewId) {
        UserResponse user = currentUser(authorization);

        InterviewEntity interview = interviewRepository.findById(interviewId)
                .orElseThrow(() -> BusinessException.notFound("면접을 찾을 수 없습니다"));
        if (!user.getId().equals(interview.getUserId())) {
            throw BusinessException.forbidden("본인의 면접만 시작할 수 있습니다.");
        }

        QuestionTailorEntity tailor = questionTailorService.requestTailor(interview, user);
        return InterviewPrepareResponse.of(tailor, interview.getSessionId(), prepareMessage(tailor));
    }

    private String prepareMessage(QuestionTailorEntity tailor) {
        if (tailor.getStatus() == TailorStatus.PENDING) {
            return "질문을 면접자에 맞게 다시 쓰는 중입니다. 준비가 끝나면 면접이 열립니다.";
        }
        if (Boolean.TRUE.equals(tailor.getChatDelivered())) {
            return "면접 준비가 끝났습니다.";
        }
        return "질문은 준비됐지만 채팅 서버에 전달하지 못했습니다. 잠시 후 다시 시도해주세요.";
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
