package repit.repit_api_server.domain.userdata.interview.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import repit.repit_api_server.domain.company.dto.CompanySnapshot;
import repit.repit_api_server.domain.company.service.CompanySnapshotFactory;
import repit.repit_api_server.domain.metadata.service.AnalysisLaunchService;
import repit.repit_api_server.domain.metadata.support.JobRole;
import repit.repit_api_server.domain.userdata.persona.support.UserJobMajors;
import repit.repit_api_server.domain.userdata.interview.dto.request.CreateInterviewRequest;
import repit.repit_api_server.domain.userdata.interview.dto.request.SaveInterviewRequest;
import repit.repit_api_server.domain.userdata.interview.dto.response.ChatAnswerResponse;
import repit.repit_api_server.domain.userdata.interview.dto.response.ChatInterviewAllResponse;
import repit.repit_api_server.domain.userdata.interview.dto.response.ChatInterviewQnAResponse;
import repit.repit_api_server.domain.userdata.interview.dto.response.ChatQuestionResponse;
import repit.repit_api_server.domain.userdata.interview.dto.response.InterviewPrepareResponse;
import repit.repit_api_server.domain.userdata.interview.dto.response.InterviewResponse;
import repit.repit_api_server.domain.userdata.interview.MultiInterviewPanel;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewPersonaEntity;
import repit.repit_api_server.domain.userdata.persona.entity.PersonaEntity;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Role;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.interview.entity.enums.AnswerMode;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewPersonaRepository;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.persona.repository.PersonaRepository;
import repit.repit_api_server.domain.userdata.answer.entity.AnswerEntity;
import repit.repit_api_server.domain.userdata.answer.repository.AnswerRepository;
import repit.repit_api_server.domain.userdata.question.entity.QuestionEntity;
import repit.repit_api_server.domain.userdata.question.entity.QuestionTailorEntity;
import repit.repit_api_server.domain.userdata.question.entity.enums.TailorStatus;
import repit.repit_api_server.domain.userdata.question.entity.enums.Type;
import repit.repit_api_server.domain.userdata.question.repository.QuestionRepository;
import repit.repit_api_server.domain.userdata.question.service.QuestionTailorService;
import repit.repit_api_server.global.auth.AuthUser;
import repit.repit_api_server.global.client.ChatServerClient;
import repit.repit_api_server.global.exception.BusinessException;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InterviewService {

    private static final Logger log = LoggerFactory.getLogger(InterviewService.class);


    private final InterviewRepository interviewRepository;
    private final QuestionRepository questionRepository;
    private final ChatServerClient chatServerClient;
    private final AnswerRepository answerRepository;
    private final PersonaRepository personaRepository;
    private final QuestionTailorService questionTailorService;
    private final InterviewPersonaRepository interviewPersonaRepository;
    private final AnalysisLaunchService analysisLaunchService;
    private final CompanySnapshotFactory companySnapshotFactory;
    private final InterviewJobSnapshotService interviewJobSnapshotService;

    /**
     * 면접을 만든다. {@link InterviewCreateService}가 트랜잭션을 열고 부른다 — N:1 면접관 명단·기업 스냅샷·요청 키가
     * 면접과 함께 저장되거나 함께 롤백된다.
     */
    public InterviewResponse createInterview(Long userId, CreateInterviewRequest request) {
        if (request.getPersonaIds() != null && !request.getPersonaIds().isEmpty()) {
            return createMultiInterview(userId, request.getPersonaIds(), request.getCompanyId(), request.getAnswerMode());
        }

        PersonaEntity persona = findPersona(request);
        if (persona.isInterviewSnapshot()) {
            throw BusinessException.unprocessable("면접 전용 면접관은 새 면접에서 선택할 수 없습니다.");
        }

        InterviewEntity interview = InterviewEntity.builder()
                .userId(userId)
                .mode(InterviewMode.SOLO)
                .answerMode(request.getAnswerMode())
                .personaId(persona.getPersonaId())
                .status(Status.IN_PROGRESS)
                .sessionId(UUID.randomUUID().toString())
                .companyId(request.getCompanyId())
                .companySnapshot(companySnapshotOf(request.getCompanyId()))
                .build();

        InterviewEntity saved = interviewRepository.save(interview);
        return InterviewResponse.from(saved, List.of(), persona);
    }

    /**
     * N:1 면접 생성. 기술 면접관 한 명에 다른 직책이 한 명 이상 붙고, 몇 명이 붙는지는 사용자가 고른다.
     *
     * <p>진행 순서는 기술 면접관이 먼저고, 나머지는 요청에 담긴 순서를 그대로 따른다. 질문 배열도
     * 이 순서를 따르고, 꼬리질문이 부모 질문 바로 뒤에 삽입되므로 한 면접관의 질문 묶음이 끝나야
     * 다음 면접관으로 넘어간다.
     */
    private InterviewResponse createMultiInterview(Long userId, List<Long> requestedIds, Long companyId, AnswerMode answerMode) {
        List<Long> personaIds = new ArrayList<>(new LinkedHashSet<>(requestedIds));
        if (personaIds.size() != requestedIds.size()) {
            throw BusinessException.unprocessable("같은 면접관을 두 번 지정할 수 없습니다.");
        }

        Map<Long, PersonaEntity> personas = personaRepository.findAllById(personaIds).stream()
                .collect(Collectors.toMap(PersonaEntity::getPersonaId, Function.identity()));
        for (Long personaId : personaIds) {
            if (personas.containsKey(personaId) && personas.get(personaId).isInterviewSnapshot()) {
                throw BusinessException.unprocessable("면접 전용 면접관은 새 면접에서 선택할 수 없습니다.");
            }
            if (!personas.containsKey(personaId)) {
                throw BusinessException.notFound("페르소나를 찾을 수 없습니다: " + personaId);
            }
        }

        List<PersonaEntity> ordered = orderForMulti(personaIds.stream().map(personas::get).toList());

        InterviewEntity saved = interviewRepository.save(InterviewEntity.builder()
                .userId(userId)
                .mode(InterviewMode.MULTI)
                .answerMode(answerMode)
                .status(Status.IN_PROGRESS)
                .sessionId(UUID.randomUUID().toString())
                .companyId(companyId)
                .companySnapshot(companySnapshotOf(companyId))
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

        // 대표 면접관은 진행 순서 맨 앞이다. 조회 응답과 같은 규칙이다.
        return InterviewResponse.from(saved, ordered.stream().map(PersonaEntity::getPersonaId).toList(),
                ordered.getFirst());
    }

    /**
     * 고른 기업을 지금 모습 그대로 복사해 둔다. 고르지 않았으면 비어 있다. 공개 기업이 아니면 404 COMPANY_UNAVAILABLE,
     * 스냅샷을 만들 수 없는 데이터면 503 COMPANY_CONTEXT_UNAVAILABLE이다 — 기업 없는 면접으로 바꿔 만들지 않는다.
     */
    private CompanySnapshot companySnapshotOf(Long companyId) {
        return companyId == null ? null : companySnapshotFactory.create(companyId);
    }

    /**
     * 진행 순서를 정한다. 기술 면접관이 맨 앞이고 나머지는 요청 순서 그대로다.
     *
     * <p>기술 면접관이 없으면 다시 쓸 원질문을 맡을 사람이 없고, 같은 직책이 둘이면 슬롯이
     * 겹쳐 면접이 성립하지 않는다. 기술 외 인원이
     * {@link MultiInterviewPanel#MIN_OTHER_COUNT}..{@link MultiInterviewPanel#MAX_OTHER_COUNT}명을
     * 벗어나도 면접을 열 수 없다. 셋 다 생성 시점에 막는다 — 여기를 지나면 질문을 만드는 쪽에서는
     * 이미 구성이 맞다고 보고 문항을 나눈다.
     *
     * <p>인원이 곧 문항 수다. 기술 면접관이 두 문항, 나머지가 한 명당 두 문항을 맡아 면접관
     * 2·3·4명이 각각 네·여섯·여덟 문항이 된다 — {@code QuestionTailorService.OTHER_QUESTION_COUNT} 참고.
     */
    private List<PersonaEntity> orderForMulti(List<PersonaEntity> personas) {
        List<PersonaEntity> tech = personas.stream()
                .filter(persona -> persona.getRole() == Role.TECH)
                .toList();
        if (tech.size() != 1) {
            throw BusinessException.unprocessable("N:1 면접에는 기술 면접관을 한 명 지정해야 합니다.");
        }

        List<PersonaEntity> others = personas.stream()
                .filter(persona -> persona.getRole() != Role.TECH)
                .toList();
        if (!MultiInterviewPanel.isOtherCountAllowed(others.size())) {
            throw BusinessException.unprocessable("N:1 면접에는 기술 외 면접관을 "
                    + MultiInterviewPanel.MIN_OTHER_COUNT + "명 이상 "
                    + MultiInterviewPanel.MAX_OTHER_COUNT + "명 이하로 지정해야 합니다.");
        }

        Set<Role> seen = EnumSet.noneOf(Role.class);
        for (PersonaEntity persona : others) {
            if (!seen.add(persona.getRole())) {
                throw BusinessException.unprocessable("같은 직책의 면접관을 두 명 지정할 수 없습니다.");
            }
        }

        List<PersonaEntity> ordered = new ArrayList<>();
        ordered.add(tech.getFirst());
        ordered.addAll(others);
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

    /**
     * 면접 시작. 웹이 부르는 진입점이다.
     *
     * <p>DB에 저장돼 있는 원질문을 페르소나와 함께 분석 서버로 보내 재작성을 요청한다.
     * 재작성은 비동기라 여기서는 접수만 하고, 콜백이 도착해 질문이 확정되면 그때
     * 채팅 서버로 면접 데이터가 넘어간다. 준비 상태는 GET /api/questions/tailor 로 확인한다.
     */
    public InterviewPrepareResponse prepareInterview(AuthUser authUser, Long interviewId) {
        InterviewEntity interview = interviewRepository.findById(interviewId)
                .orElseThrow(() -> BusinessException.notFound("면접을 찾을 수 없습니다"));
        if (!authUser.id().equals(interview.getUserId())) {
            throw BusinessException.forbidden("본인의 면접만 시작할 수 있습니다.");
        }
        if (requiresJobSnapshot(authUser)) {
            interview = interviewJobSnapshotService.capture(authUser, interviewId);
        }
        // 쓸 수 있는 종합 데이터가 없으면(예전 분석만 있거나, 분석 서버가 지금 것을 거부했으면) 종합 데이터부터 요청한다.
        // 질문 준비는 그 분석을 기다린다.
        analysisLaunchService.launchIfMissing(authUser);
        // N:1은 질문 재작성이 아니라 신규 생성이 섞인 multi tailor로 간다. 갈림길은 서비스 안에 있다.
        QuestionTailorEntity tailor = questionTailorService.requestTailor(interview, authUser.user());
        return InterviewPrepareResponse.of(tailor, interview);
    }

    /**
     * 준비가 실패로 끝난 면접을 다시 준비한다.
     *
     * <p>실패한 건을 그대로 두면 면접 시작을 다시 눌러도 그 실패가 그대로 돌아온다. 특히 N:1은
     * 폴백할 원질문이 없어 한 번 실패하면 면접을 새로 만드는 것 말고는 길이 없었다.
     */
    public InterviewPrepareResponse retryPreparation(AuthUser authUser, Long interviewId) {
        InterviewEntity interview = interviewRepository.findById(interviewId)
                .orElseThrow(() -> BusinessException.notFound("면접을 찾을 수 없습니다"));
        if (!authUser.id().equals(interview.getUserId())) {
            throw BusinessException.forbidden("본인의 면접만 다시 준비할 수 있습니다.");
        }
        // 질문을 다시 만드는 재시도는 처음 시작과 같은 직무 고정을 거친다. 이미 고정됐으면 그대로 둔다.
        if (requiresJobSnapshot(authUser)) {
            // 콜백을 기다리다 시간만 지난 건이 실패로 닫혀야 질문을 다시 만드는 재시도인지 알 수 있다.
            questionTailorService.expireTimedOutPreparation(interviewId);
            interview = interviewJobSnapshotService.captureForRetry(authUser, interviewId);
        }

        // 처음 시작할 때 종합 데이터를 요청하지 못했거나, 그 사이 분석 서버가 지금 것을 거부했으면 여기서 요청한다.
        analysisLaunchService.launchIfMissing(authUser);
        QuestionTailorEntity tailor = questionTailorService.retryPreparation(interview, authUser.user());
        return InterviewPrepareResponse.of(tailor, interview);
    }

    /** 직무명을 고정해야 하는 직무(일반 직군)인지. 고정은 면접 전용 면접관 복제로 한다. */
    private static boolean requiresJobSnapshot(AuthUser authUser) {
        return JobRole.find(UserJobMajors.normalize(authUser.user().getMajor()))
                .map(JobRole::requiresJobTitle).orElse(false);
    }

    /**
     * 사용자의 면접 목록. 면접마다 대표 면접관을 붙인다.
     *
     * <p>N:1 면접관 명단과 면접관은 면접마다 따로 읽지 않고 한 번에 읽어 면접별로 나눈다.
     * 그러지 않으면 목록이 길어지는 만큼 쿼리가 늘어난다.
     */
    public List<InterviewResponse> getAllInterviewsByUserId(Long userId) {
        List<InterviewEntity> interviews = interviewRepository.findAllByUserId(userId);

        List<Long> multiInterviewIds = interviews.stream()
                .filter(interview -> interview.getMode() == InterviewMode.MULTI)
                .map(InterviewEntity::getInterviewId)
                .toList();
        Map<Long, List<Long>> personaIdsByInterview = multiInterviewIds.isEmpty()
                ? Map.of()
                : interviewPersonaRepository
                        .findAllByInterviewIdInOrderByInterviewIdAscPersonaOrderAsc(multiInterviewIds).stream()
                        .collect(Collectors.groupingBy(InterviewPersonaEntity::getInterviewId,
                                Collectors.mapping(InterviewPersonaEntity::getPersonaId, Collectors.toList())));

        Map<Long, Long> representativeIdByInterview = new HashMap<>();
        for (InterviewEntity interview : interviews) {
            Long personaId = representativePersonaId(interview,
                    personaIdsByInterview.getOrDefault(interview.getInterviewId(), List.of()));
            if (personaId != null) {
                representativeIdByInterview.put(interview.getInterviewId(), personaId);
            }
        }
        Map<Long, PersonaEntity> personaById = representativeIdByInterview.isEmpty()
                ? Map.of()
                : personaRepository.findAllById(new LinkedHashSet<>(representativeIdByInterview.values())).stream()
                        .collect(Collectors.toMap(PersonaEntity::getPersonaId, Function.identity()));

        return interviews.stream()
                .map(interview -> {
                    Long representativeId = representativeIdByInterview.get(interview.getInterviewId());
                    return InterviewResponse.from(
                            interview,
                            personaIdsByInterview.getOrDefault(interview.getInterviewId(), List.of()),
                            representativeId == null ? null : personaById.get(representativeId));
                })
                .toList();
    }

    public InterviewResponse getInterviewById(Long userId, Long interviewId) {
        InterviewEntity interview = interviewRepository.findById(interviewId)
                .orElseThrow(() -> BusinessException.notFound("면접을 찾을 수 없습니다"));
        verifyOwner(interview, userId);
        List<Long> personaIds = personaIdsOf(interview);
        Long representativeId = representativePersonaId(interview, personaIds);
        PersonaEntity persona = representativeId == null
                ? null
                : personaRepository.findById(representativeId).orElse(null);
        return InterviewResponse.from(interview, personaIds, persona);
    }

    /**
     * 면접을 대표하는 면접관 id. 1:1은 그 면접의 면접관, N:1은 진행 순서 맨 앞 면접관이다.
     * 피드백 응답이 성향·난이도를 대표 한 명으로 읽는 것과 같은 규칙이다. 걸린 면접관이 없으면 null이다.
     *
     * @param multiPersonaIds 진행 순서대로 정렬된 N:1 면접관 명단. 1:1은 빈 목록이다.
     */
    private static Long representativePersonaId(InterviewEntity interview, List<Long> multiPersonaIds) {
        if (interview.getMode() == InterviewMode.MULTI) {
            return multiPersonaIds.isEmpty() ? null : multiPersonaIds.getFirst();
        }
        return interview.getPersonaId();
    }

    /**
     * 면접은 본인 것만 볼 수 있다.
     *
     * <p>면접 id는 순번이라 옆 번호를 넣어보는 것만으로 남의 면접에 닿는다. 그 안에는 면접
     * 전문과 답변이 들어 있어, 소유자를 견주지 않으면 조회 한 번으로 그대로 새어나간다.
     */
    private void verifyOwner(InterviewEntity interview, Long userId) {
        if (!userId.equals(interview.getUserId())) {
            throw BusinessException.forbidden("본인의 면접만 볼 수 있습니다.");
        }
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

    /**
     * 면접의 전체 기록. 어디서 읽을지는 면접이 끝났는지에 달려 있다.
     *
     * <p>채팅 서버는 면접이 끝나면 기록을 /api/interviews/result로 넘기고 곧바로 세션을 지운다.
     * 그때부터 우리 DB가 유일한 원본이라, 저장된 기록이 있으면 그쪽에서 읽는다. 채팅 서버에
     * 물으면 이미 없는 세션이라 실패한다.
     *
     * <p>진행 중인 면접은 아직 우리 DB에 아무것도 없다. 채팅 면접 질문은 결과가 넘어올 때
     * 한꺼번에 들어오기 때문이다. 그 사이에는 채팅 서버 세션이 현재 상태를 들고 있다.
     */
    public ChatInterviewAllResponse getChatInterview(Long userId, Long interviewId) {
        InterviewEntity interview = interviewRepository.findById(interviewId)
                .orElseThrow(() -> BusinessException.notFound("면접을 찾을 수 없습니다"));
        verifyOwner(interview, userId);

        List<QuestionEntity> questions =
                questionRepository.findAllByInterviewIdOrderByQuestionIdAsc(interviewId);
        if (!questions.isEmpty()) {
            return fromStored(interview, questions);
        }

        return chatServerClient.getInterview(interview.getSessionId());
    }

    /**
     * 저장된 기록을 채팅 서버가 내려주던 형태로 되돌린다.
     *
     * <p>질문 번호는 채팅 서버 번호가 아니라 우리 PK로 내려간다. 채팅 서버 번호는 면접 안에서만
     * 유일해 밖에서는 가리키는 것이 없고, 피드백 문항도 우리 PK로 매겨져 결과 화면에서 둘을
     * 맞춰봐야 하기 때문이다.
     */
    private ChatInterviewAllResponse fromStored(InterviewEntity interview, List<QuestionEntity> questions) {
        Map<Long, AnswerEntity> answerByQuestionId =
                answerRepository.findAllByInterviewIdOrderByAnswerIdAsc(interview.getInterviewId()).stream()
                        .collect(Collectors.toMap(AnswerEntity::getQuestionId, Function.identity(),
                                // 한 질문에 답변이 둘일 이유는 없지만, 있다면 나중 것이 최종 답변이다.
                                (first, second) -> second));

        List<ChatInterviewQnAResponse> qnAs = questions.stream()
                .map(question -> new ChatInterviewQnAResponse(
                        toChatQuestion(question),
                        toChatAnswer(interview, question, answerByQuestionId.get(question.getQuestionId()))))
                .toList();

        return new ChatInterviewAllResponse(
                interview.getSessionId(),
                interview.getInterviewId(),
                interview.getUserId(),
                interview.getStatus(),
                // 끝난 면접이라 진행 위치는 마지막 질문 다음이다.
                questions.size(),
                interview.getCreatedAt(),
                qnAs);
    }

    private ChatQuestionResponse toChatQuestion(QuestionEntity question) {
        return new ChatQuestionResponse(
                question.getQuestionId(),
                question.getParentId(),
                question.getType(),
                question.getIntention(),
                question.getContent(),
                question.getPersonaId(),
                question.getCreatedAt());
    }

    /** 답하지 않고 넘어간 질문은 답변이 비어 있다. 채팅 서버도 그 자리를 비워서 내려준다. */
    private ChatAnswerResponse toChatAnswer(InterviewEntity interview, QuestionEntity question, AnswerEntity answer) {
        if (answer == null) {
            return null;
        }
        return new ChatAnswerResponse(
                interview.getInterviewId(),
                question.getQuestionId(),
                answer.getUserId(),
                answer.getResponseTime(),
                answer.getContent(),
                answer.getCreatedAt());
    }

    /**
     * 채팅 서버가 면접을 마치고 넘겨주는 전체 기록을 저장한다.
     *
     * <p>채팅 서버는 이 요청을 보낸 직후 세션을 지운다. 여기가 마지막 기회라 실패하면 면접
     * 내용이 사라진다. 그래서 질문·답변과 종료 상태를 한 트랜잭션에서 함께 남긴다 — 중간에 실패하면 모두 롤백되고
     * 면접은 진행 중으로 남아, 다시 보낸 기록이 처음부터 저장된다.
     *
     * <p>채팅 서버는 저장이 끝났는데 응답을 받지 못하면 같은 기록을 다시 보낸다. 이미 저장한 면접이면 아무것도 바꾸지
     * 않는다. 지우고 다시 넣으면 질문 PK가 바뀌어 그 질문에 매달린 채점·녹음과 어긋난다. 내용이 달라도, 상태가 반대로 와도
     * 처음 것을 둔다.
     *
     * <p>새 기록의 상태는 COMPLETED 또는 ABANDONED만 받는다. 중단 기록도 질문·답변을 그대로 남긴다 — 채점·분석을 하지 않을
     * 뿐 기록은 보존한다. 무엇을 실행할지는 저장이 끝난 뒤 저장된 상태를 다시 읽어 정한다({@link ChatInterviewResultService}).
     */
    @Transactional
    public void saveInterview(SaveInterviewRequest request) {
        InterviewEntity interview = interviewRepository.findById(request.getInterviewId())
                .orElseThrow(() -> BusinessException.notFound("면접을 찾을 수 없습니다"));

        // 이미 종료 기록을 저장한 면접이다. 다시 온 요청의 상태가 무엇이든 처음 저장한 기록·질문 PK·최종 상태를 그대로 둔다.
        // 상태 검사보다 먼저 본다 — 상태를 빠뜨린 재전송을 422로 돌려보내면 채팅 서버가 이미 끝난 전달을 계속 다시 보낸다.
        if (interview.getStatus() != Status.IN_PROGRESS) {
            log.info("이미 기록을 저장한 면접이라 다시 저장하지 않습니다. interviewId={}, 저장된 상태={}, 받은 상태={}, 받은 문항 수={}",
                    interview.getInterviewId(), interview.getStatus(), request.getStatus(),
                    request.getQnaRequests() == null ? 0 : request.getQnaRequests().size());
            return;
        }

        // 새 종료 기록은 채팅 서버가 확정한 상태를 밝혀야 한다. 비었거나 진행 중이면 COMPLETED로 고쳐 받지 않는다 — 그렇게
        // 받으면 중단된 면접까지 채점·영상 분석 대상이 된다. 차지(claim)하기 전에 거절해 면접은 진행 중으로 남는다.
        Status status = request.getStatus();
        if (status != Status.COMPLETED && status != Status.ABANDONED) {
            throw BusinessException.unprocessable("면접 종료 상태(status)는 COMPLETED 또는 ABANDONED여야 합니다.");
        }
        if (interviewRepository.claimResultSave(interview.getInterviewId(), status) == 0) {
            log.info("이미 기록을 저장한 면접이라 다시 저장하지 않습니다. interviewId={}, 받은 상태={}, 받은 문항 수={}",
                    interview.getInterviewId(), request.getStatus(),
                    request.getQnaRequests() == null ? 0 : request.getQnaRequests().size());
            return;
        }

        // 세션과 상태는 비어 오면 덮어쓰지 않는다. 둘 다 not null 컬럼이라 null을 넣으면 저장이
        // 통째로 실패하고, 그 500이 채팅 서버의 완료 처리까지 끊어 세션을 남긴 채로 끝난다.
        if (request.getSessionId() != null && !request.getSessionId().isBlank()) {
            interview.setSessionId(request.getSessionId());
        }
        interview.setStatus(status);
        interviewRepository.save(interview);

        answerRepository.deleteAllByInterviewId(interview.getInterviewId());
        questionRepository.deleteAllByInterviewId(interview.getInterviewId());

        List<SaveInterviewRequest.QnA> qnAs = request.getQnaRequests() == null
                ? List.of()
                : request.getQnaRequests();

        Map<Long, Long> questionIdByChatId = saveQuestions(interview, qnAs);
        saveAnswers(interview, qnAs, questionIdByChatId);
    }

    /**
     * 지금 DB에 저장된 면접 상태. 기록 저장이 커밋된 뒤 무엇을 실행할지 정할 때 쓴다 — 받은 요청의 상태가 아니라 처음 확정된
     * 상태를 따라야 반대 상태로 다시 온 기록이 채점을 열지 못한다. 면접이 없으면 null이다.
     */
    public Status findStoredStatus(Long interviewId) {
        return interviewRepository.findById(interviewId).map(InterviewEntity::getStatus).orElse(null);
    }

    /**
     * 질문을 저장하고 채팅 서버 번호 -> 우리 PK 매핑을 돌려준다.
     *
     * <p>채팅 서버 번호는 PK로 쓸 수 없다. ORIGINAL은 분석 결과 안의 지역 번호(1..N)라 면접이
     * 다르면 같은 번호가 다시 나오고, FOLLOW는 채팅 서버가 매기는 음수다.
     *
     * <p>질문 종류와 부모, 시각은 채팅 서버가 보내지 않아 여기서 되짚는다. 채팅 서버는 꼬리질문을
     * 답한 질문 바로 뒤에 끼워 넣으므로, 목록에서 직전에 나온 원질문이 곧 부모다. 부모는 늘 앞서
     * 저장되므로 한 번 훑으면서 우리 PK로 옮길 수 있다. 그래서 saveAll로 묶지 않고 한 건씩
     * 저장해 매핑을 채운다.
     */
    private Map<Long, Long> saveQuestions(InterviewEntity interview, List<SaveInterviewRequest.QnA> qnAs) {
        Map<Long, Long> questionIdByChatId = new HashMap<>();
        // 직전에 저장한 원질문의 우리 PK. 바로 뒤에 오는 꼬리질문의 부모가 된다.
        Long lastOriginalId = null;

        for (SaveInterviewRequest.QnA qnA : qnAs) {
            SaveInterviewRequest.Question question = qnA.getQuestion();
            if (question == null || question.getId() == null) {
                continue;
            }

            Type type = typeOf(question.getId());
            QuestionEntity saved = questionRepository.save(QuestionEntity.builder()
                    .interviewId(interview.getInterviewId())
                    .chatQuestionId(question.getId())
                    .parentId(type == Type.FOLLOW ? lastOriginalId : null)
                    .personaId(question.getPersonaId())
                    .type(type)
                    .intention(intentionOf(question, type))
                    .content(question.getQuestion())
                    // 채팅 서버는 질문마다의 시각을 보내지 않는다. 저장 순서가 곧 면접 순서라
                    // 받은 시각으로 채운다. 순서를 읽는 쪽은 question_id 오름차순을 쓴다.
                    .createdAt(LocalDateTime.now())
                    .build());

            questionIdByChatId.put(question.getId(), saved.getQuestionId());
            if (type == Type.ORIGINAL) {
                lastOriginalId = saved.getQuestionId();
            }
        }

        return questionIdByChatId;
    }

    /**
     * 질문 종류. 채팅 서버가 보내지 않아 번호로 되짚는다.
     *
     * <p>원질문 번호는 분석 결과 안의 지역 번호(1..N)라 늘 양수고, 꼬리질문 번호는 채팅 서버가
     * 음수로 매긴다. 그 부호가 둘을 가르는 유일한 단서다.
     */
    private Type typeOf(Long chatQuestionId) {
        return chatQuestionId < 0 ? Type.FOLLOW : Type.ORIGINAL;
    }

    // 답변 시각이 비어 와도 저장까지 실패하지는 않게 한다. 여기서 막히면 면접 기록이 통째로 사라진다.
    private LocalDateTime orNow(LocalDateTime createdAt) {
        return createdAt == null ? LocalDateTime.now() : createdAt;
    }

    /**
     * 채팅 서버가 찍은 시각을 이 서버의 시각으로 옮긴다.
     *
     * <p>채팅 서버는 UTC 컨테이너에서 시간대 없이 LocalDateTime.now()를 보낸다. 그대로 저장하면 이 서버가 UTC가
     * 아닐 때(로컬 KST) 답변만 이 서버가 찍은 질문 시각보다 9시간 이르게 남는다.
     */
    // ponytail: 채팅 서버가 UTC라고 가정한다. 다른 시간대에서 돌리면 어긋나므로, 채팅 서버가 오프셋을 실어 보내면 그 값을 쓴다.
    private LocalDateTime fromChatClock(LocalDateTime chatTime) {
        return chatTime == null ? null : chatTime.atOffset(ZoneOffset.UTC)
                .atZoneSameInstant(ZoneId.systemDefault())
                .toLocalDateTime();
    }

    /**
     * 질문 의도. 채점은 이 값 하나를 기준으로 이뤄진다.
     *
     * <p>intention이 오면 그것을 쓴다. 원질문은 면접을 열 때 우리가 넘긴 값이 그대로 돌아온다.
     * 채팅 서버가 아직 intention을 따로 싣지 않으면, 꼬리질문은 그때 정한 의도가 category로 실려 오고
     * 원질문은 모범답안으로 대신한다 — intention이 생기기 전의 채점 기준이 그것이었다.
     */
    private String intentionOf(SaveInterviewRequest.Question question, Type type) {
        if (question.getIntention() != null && !question.getIntention().isBlank()) {
            return question.getIntention();
        }
        String expectedAnswer = question.getExpectedAnswer();
        if (type == Type.ORIGINAL && expectedAnswer != null && !expectedAnswer.isBlank()) {
            return expectedAnswer;
        }
        return question.getCategory();
    }

    private void saveAnswers(InterviewEntity interview, List<SaveInterviewRequest.QnA> qnAs,
                             Map<Long, Long> questionIdByChatId) {
        List<AnswerEntity> answers = new ArrayList<>();

        for (SaveInterviewRequest.QnA qnA : qnAs) {
            SaveInterviewRequest.Answer answer = qnA.getAnswer();
            if (answer == null) {
                continue;
            }

            Long questionId = questionIdByChatId.get(answer.getQuestionId());
            if (questionId == null) {
                // 질문 없는 답변은 어디에도 매달 수 없다. 나머지는 저장해야 하므로 이 건만 건너뛴다.
                log.warn("답변에 대응하는 질문이 없어 저장하지 않습니다. interviewId={}, chatQuestionId={}",
                        interview.getInterviewId(), answer.getQuestionId());
                continue;
            }

            answers.add(AnswerEntity.builder()
                    .interviewId(interview.getInterviewId())
                    .questionId(questionId)
                    .userId(interview.getUserId())
                    .responseTime(answer.getResponseTime())
                    .content(answer.getAnswerContent())
                    .createdAt(orNow(fromChatClock(answer.getAnswerCreatedAt())))
                    .build());
        }

        answerRepository.saveAll(answers);
    }
}
