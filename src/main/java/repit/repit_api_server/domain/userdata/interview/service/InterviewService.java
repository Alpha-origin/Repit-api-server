package repit.repit_api_server.domain.userdata.interview.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import repit.repit_api_server.domain.userdata.interview.dto.request.CreateInterviewRequest;
import repit.repit_api_server.domain.userdata.interview.dto.request.SaveInterviewRequest;
import repit.repit_api_server.domain.userdata.interview.dto.response.ChatInterviewAllResponse;
import repit.repit_api_server.domain.userdata.interview.dto.response.InterviewPrepareResponse;
import repit.repit_api_server.domain.userdata.interview.dto.response.InterviewResponse;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewPersonaEntity;
import repit.repit_api_server.domain.userdata.persona.entity.PersonaEntity;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Role;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewPersonaRepository;
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

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InterviewService {

    // N:1 면접은 직책마다 한 명씩, 이 순서로 진행한다.
    private static final List<Role> MULTI_ROLES = List.of(Role.TECH, Role.HR, Role.CEO);

    private final InterviewRepository interviewRepository;
    private final QuestionRepository questionRepository;
    private final ChatServerClient chatServerClient;
    private final AuthServerClient authServerClient;
    private final AnswerRepository answerRepository;
    private final PersonaRepository personaRepository;
    private final QuestionTailorService questionTailorService;
    private final InterviewPersonaRepository interviewPersonaRepository;

    public InterviewResponse createInterview(String authorization, CreateInterviewRequest request) {
        UserResponse user = currentUser(authorization);

        if (request.getPersonaIds() != null && !request.getPersonaIds().isEmpty()) {
            return createMultiInterview(user, request.getPersonaIds());
        }

        PersonaEntity persona = findPersona(request);

        InterviewEntity interview = InterviewEntity.builder()
                .userId(user.getId())
                .mode(InterviewMode.SOLO)
                .personaId(persona.getPersonaId())
                .status(Status.IN_PROGRESS)
                .sessionId(UUID.randomUUID().toString())
                .build();

        InterviewEntity saved = interviewRepository.save(interview);
        return InterviewResponse.from(saved);
    }

    /**
     * N:1 면접 생성. 면접관은 기술·인사·CEO 한 명씩이다.
     *
     * <p>진행 순서는 요청 순서가 아니라 직책 순서로 정한다. 질문 배열도 이 순서를 따르고,
     * 꼬리질문이 부모 질문 바로 뒤에 삽입되므로 한 면접관의 질문 묶음이 끝나야 다음 면접관으로 넘어간다.
     */
    private InterviewResponse createMultiInterview(UserResponse user, List<Long> requestedIds) {
        List<Long> personaIds = new ArrayList<>(new LinkedHashSet<>(requestedIds));
        if (personaIds.size() != requestedIds.size()) {
            throw BusinessException.unprocessable("같은 면접관을 두 번 지정할 수 없습니다.");
        }

        Map<Long, PersonaEntity> personas = personaRepository.findAllById(personaIds).stream()
                .collect(Collectors.toMap(PersonaEntity::getPersonaId, Function.identity()));
        for (Long personaId : personaIds) {
            if (!personas.containsKey(personaId)) {
                throw BusinessException.notFound("페르소나를 찾을 수 없습니다: " + personaId);
            }
        }

        List<PersonaEntity> ordered = orderByRole(personas.values());

        InterviewEntity saved = interviewRepository.save(InterviewEntity.builder()
                .userId(user.getId())
                .mode(InterviewMode.MULTI)
                .status(Status.IN_PROGRESS)
                .sessionId(UUID.randomUUID().toString())
                .build());

        List<InterviewPersonaEntity> members = new ArrayList<>();
        for (int order = 0; order < ordered.size(); order++) {
            members.add(InterviewPersonaEntity.builder()
                    .interviewId(saved.getInterviewId())
                    .personaId(ordered.get(order).getPersonaId())
                    .personaOrder(order)
                    .build());
        }
        interviewPersonaRepository.saveAll(members);

        return InterviewResponse.from(saved, ordered.stream().map(PersonaEntity::getPersonaId).toList());
    }

    /** 직책이 하나라도 비거나 겹치면 면접이 성립하지 않으므로 생성 시점에 막는다. */
    private List<PersonaEntity> orderByRole(Iterable<PersonaEntity> personas) {
        Map<Role, List<PersonaEntity>> byRole = new EnumMap<>(Role.class);
        for (PersonaEntity persona : personas) {
            byRole.computeIfAbsent(persona.getRole(), role -> new ArrayList<>()).add(persona);
        }

        List<PersonaEntity> ordered = new ArrayList<>();
        for (Role role : MULTI_ROLES) {
            List<PersonaEntity> matched = byRole.get(role);
            if (matched == null || matched.size() != 1) {
                throw BusinessException.unprocessable(
                        "N:1 면접은 기술·인사·CEO 면접관을 한 명씩 지정해야 합니다.");
            }
            ordered.add(matched.getFirst());
        }
        return ordered;
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
        // N:1은 질문 재작성이 아니라 신규 생성이 섞인 multi tailor를 써야 한다. 분석 서버 스펙이 확정되기 전까지는
        // 1:1용 재작성을 태우면 인사·CEO 질문 없이 면접이 열리므로 아예 막는다.
        if (interview.getMode() == InterviewMode.MULTI) {
            throw BusinessException.unprocessable("N:1 면접 시작은 아직 준비 중입니다.");
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

        return interviewRepository.findAllByUserId(user.getId()).stream()
                .map(interview -> InterviewResponse.from(interview, personaIdsOf(interview)))
                .toList();
    }

    public InterviewResponse getInterviewById(Long interviewId) {
        InterviewEntity interview = interviewRepository.findById(interviewId).orElse(null);
        assert interview != null;
        return InterviewResponse.from(interview, personaIdsOf(interview));
    }

    /** N:1 면접관 목록. 1:1은 interview.personaId 하나로 끝나므로 조회하지 않는다. */
    private List<Long> personaIdsOf(InterviewEntity interview) {
        if (interview.getMode() != InterviewMode.MULTI) {
            return List.of();
        }
        return interviewPersonaRepository.findAllByInterviewIdOrderByPersonaOrderAsc(interview.getInterviewId())
                .stream()
                .map(InterviewPersonaEntity::getPersonaId)
                .toList();
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
