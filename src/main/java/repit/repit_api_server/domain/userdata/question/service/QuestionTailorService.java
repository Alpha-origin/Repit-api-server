package repit.repit_api_server.domain.userdata.question.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import repit.repit_api_server.domain.metadata.dto.response.ProjectSummaryResponse;
import repit.repit_api_server.domain.userdata.interview.MultiInterviewPanel;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewPersonaEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewPersonaRepository;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.metadata.sse.SseNotifier;
import repit.repit_api_server.domain.userdata.interview.dto.response.InterviewReadyResponse;
import repit.repit_api_server.domain.userdata.interview.service.ChatInterviewHandoffService;
import repit.repit_api_server.domain.userdata.persona.entity.PersonaEntity;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Role;
import repit.repit_api_server.domain.userdata.persona.repository.PersonaRepository;
import repit.repit_api_server.domain.userdata.question.dto.request.QuestionCycleCallbackRequest;
import repit.repit_api_server.domain.userdata.question.dto.request.QuestionTailorCallbackRequest;
import repit.repit_api_server.domain.userdata.question.dto.request.QuestionTailorMultiCallbackRequest;
import repit.repit_api_server.domain.userdata.question.dto.request.QuestionTailorMultiRequest;
import repit.repit_api_server.domain.userdata.question.dto.request.QuestionTailorRequest;
import repit.repit_api_server.domain.userdata.question.dto.response.QuestionTailorAcceptedResponse;
import repit.repit_api_server.domain.userdata.question.dto.response.QuestionTailorResponse;
import repit.repit_api_server.domain.userdata.question.dto.response.TailoredQuestionResponse;
import repit.repit_api_server.domain.userdata.question.entity.QuestionTailorEntity;
import repit.repit_api_server.domain.userdata.question.entity.enums.TailorStatus;
import repit.repit_api_server.domain.userdata.question.preparation.FailureStage;
import repit.repit_api_server.domain.userdata.question.preparation.PreparationState;
import repit.repit_api_server.domain.userdata.question.preparation.PreparationStatus;
import repit.repit_api_server.domain.userdata.question.repository.QuestionTailorRepository;
import repit.repit_api_server.global.client.AiServerClient;
import repit.repit_api_server.global.exception.BusinessException;
import repit.repit_api_server.global.response.UserResponse;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 면접 시작 시 도는 질문 재작성 흐름.
 *
 * <p>질문 풀에서 꺼낸 세트를 페르소나와 함께 분석 서버로 보내고, 콜백으로 돌아온 재작성 질문을
 * 원질문과 함께 저장한 뒤, 그 전체를 채팅 서버로 넘긴다. 꺼낼 세트가 없으면 다음 질문 사이클을
 * 기다렸다가 사이클 콜백에서 이어간다. 재작성이 실패해도 원질문은 유효한
 * 산출물이라 어떤 경로로 끝나든 면접에 쓸 질문이 남고 채팅 서버로도 넘어간다.
 */
@Service
@RequiredArgsConstructor
public class QuestionTailorService {

    private static final Logger log = LoggerFactory.getLogger(QuestionTailorService.class);

    private static final String CALLBACK_PATH = "/api/questions/tailor/callback";
    private static final String MULTI_CALLBACK_PATH = "/api/questions/tailor/multi/callback";
    private static final String STATUS_SUCCEEDED = "succeeded";
    // 인증 서버의 전공 값 앞에 붙는 접두사. 떼면 면접관 전공(Major)과 같은 형식이 된다.
    private static final String AUTH_MAJOR_PREFIX = "MAJOR_";

    // 기술 외 면접관 한 명이 맡을 문항 수. 분석 서버 기본값과 같다. 면접관이 늘면 이만큼씩 늘어난다.
    private static final int OTHER_QUESTION_COUNT = 2;

    private final QuestionTailorRepository questionTailorRepository;
    private final InterviewRepository interviewRepository;
    private final InterviewPersonaRepository interviewPersonaRepository;
    private final PersonaRepository personaRepository;
    private final QuestionPoolService questionPoolService;
    private final AiServerClient aiServerClient;
    private final ChatInterviewHandoffService chatInterviewHandoffService;
    private final SseNotifier sseNotifier;
    private final ObjectMapper objectMapper;

    @Value("${app.callback-base-url}")
    private String callbackBaseUrl;

    // 이 시간을 넘도록 콜백이 오지 않으면 실패로 간주하고 원질문으로 진행시킨다.
    @Value("${app.question-tailor.pending-timeout:2m}")
    private Duration pendingTimeout;

    // 이 시간을 넘도록 새 질문 사이클이 오지 않으면 기다리던 준비를 실패로 닫는다. 재작성 시간은 따로 센다.
    @Value("${app.question-tailor.waiting-timeout:3m}")
    private Duration waitingTimeout;

    /**
     * 면접 시작 요청이 들어오면 원질문 + 페르소나를 분석 서버로 보낸다.
     *
     * <p>외부 서버 호출이 들어가므로 트랜잭션으로 감싸지 않는다. 감싸면 느린 HTTP 응답을
     * 기다리는 내내 DB 커넥션을 붙잡게 된다.
     *
     * <p>같은 면접을 두 번 시작해도 작업이 겹치지 않도록, 진행 중이거나 이미 끝난 건이 있으면
     * 새로 요청하지 않고 그 상태를 그대로 돌려준다.
     */
    public QuestionTailorEntity requestTailor(InterviewEntity interview, UserResponse user) {
        QuestionTailorEntity existing = latestTailor(interview.getInterviewId());
        if (existing != null) {
            expireIfTimedOut(existing);
            // 이미 확정된 건은 남은 뒷단만 마저 밟는다. 여기서 새 작업을 만들면 같은 면접이 두 번 준비된다.
            completePreparation(existing);
            return existing;
        }

        return startTailor(interview, user.getMajor(), null);
    }

    /**
     * 면접 준비를 다시 시도한다.
     *
     * <p>멈춘 단계에 따라 하는 일이 다르다. 질문을 만들지 못했으면 처음부터 다시 만들고,
     * 만들어둔 질문을 넘기지 못한 것뿐이면 전달만 다시 한다 — 그 경우까지 새로 만들면
     * 이미 준비됐던 질문이 까닭 없이 바뀐다.
     *
     * <p>준비 중이거나 이미 열린 면접에는 새 작업을 만들지 않는다. 만들면 채팅 서버에 같은
     * 면접을 여는 요청이 한 번 더 가고, 질문도 갈아치워진다.
     */
    public QuestionTailorEntity retryPreparation(InterviewEntity interview, UserResponse user) {
        QuestionTailorEntity existing = latestTailor(interview.getInterviewId());
        if (existing == null) {
            throw BusinessException.conflict("아직 시작하지 않은 면접입니다. 면접 시작을 먼저 요청해주세요.");
        }

        // 콜백을 기다리다 시간만 지난 건은 여기서 실패로 확정한다. 그러지 않으면 이미 가망이
        // 없는 작업 때문에 재시도가 "준비 중"에 막힌다.
        expireIfTimedOut(existing);

        PreparationState state = PreparationState.of(existing);
        if (state.status() == PreparationStatus.PREPARING) {
            throw BusinessException.conflict("질문을 준비하는 중입니다. 잠시 후 다시 시도해주세요.");
        }
        if (state.status() == PreparationStatus.READY) {
            // 이미 열려 있다. 다시 만들 것이 없으니 지금 상태를 그대로 돌려준다.
            return existing;
        }
        if (state.failureStage() == FailureStage.CHAT_DELIVERY) {
            deliverToChatServer(existing);
            return existing;
        }
        return startTailor(interview, user.getMajor(), null);
    }

    private QuestionTailorEntity latestTailor(Long interviewId) {
        return questionTailorRepository.findTopByInterviewIdOrderByCreatedAtDesc(interviewId).orElse(null);
    }

    /**
     * 질문 풀에서 세트를 꺼내 분석 서버에 질문 준비를 접수한다. 1:1과 N:1의 갈림길이 여기다.
     *
     * @param userMajor 사용자 전공. 콜백에서 이어갈 때는 사용자 정보가 없어 종합 데이터에 실었던 값을 쓴다.
     * @param target    사이클을 기다리던 준비 건. 있으면 새로 만들지 않고 이 건을 채운다.
     */
    private QuestionTailorEntity startTailor(InterviewEntity interview, String userMajor, QuestionTailorEntity target) {
        QuestionPoolService.DrawnSet drawn = questionPoolService.takeSet(
                interview.getUserId(), interview.getMode(), interview.getInterviewId());

        QuestionTailorEntity tailor = target != null ? target : QuestionTailorEntity.builder()
                .interviewId(interview.getInterviewId())
                .userId(interview.getUserId())
                .mode(interview.getMode())
                .chatDelivered(false)
                .build();
        // 재작성본이 어느 종합 데이터에서 나왔는지 되짚고, 웹이 그 작업으로 구독한 자리에 준비 상태를 알린다.
        tailor.setAnalysisJobId(drawn.profileJobId());

        if (drawn.waiting()) {
            return waitForCycle(interview, tailor);
        }

        String major = userMajor != null ? userMajor : drawn.major();
        QuestionTailorAcceptedResponse accepted = interview.getMode() == InterviewMode.MULTI
                ? requestMultiTailor(interview, major, drawn)
                : requestSoloTailor(interview, major, drawn);

        tailor.setJobId(accepted == null ? null : accepted.getJobId());
        tailor.setStatus(TailorStatus.PENDING);
        // N:1이면 기술 면접관에게 넘긴 원질문만 남는다. 나머지 면접관 몫은 아직 존재하지 않는다.
        // 재작성이 실패해 원질문으로 되돌아가도 문항 수는 세트 그대로다.
        tailor.setSourceQuestions(drawn.questions());
        return questionTailorRepository.save(tailor);
    }

    private QuestionTailorAcceptedResponse requestSoloTailor(InterviewEntity interview, String major,
                                                             QuestionPoolService.DrawnSet drawn) {
        QuestionTailorRequest.Profile profile = resolveProfile(major, interview.getPersonaId());

        return aiServerClient.tailorQuestions(QuestionTailorRequest.builder()
                .interviewId(String.valueOf(interview.getInterviewId()))
                .userId(String.valueOf(interview.getUserId()))
                .profile(profile)
                .questions(drawn.questions().stream().map(this::toRequestQuestion).toList())
                .callbackUrl(callbackBaseUrl + CALLBACK_PATH)
                .build());
    }

    /**
     * 꺼낼 세트가 없어 다음 질문 사이클을 기다린다. 사이클 콜백이 {@link #handleCycleCallback}에서 이어준다.
     *
     * <p>기다린다는 사실을 구독에 알린다. 평소보다 오래 걸리는데 아무 말이 없으면 사용자는 멈춘 줄 안다.
     */
    private QuestionTailorEntity waitForCycle(InterviewEntity interview, QuestionTailorEntity tailor) {
        tailor.setStatus(TailorStatus.WAITING);
        tailor.setJobId(null);
        QuestionTailorEntity saved = questionTailorRepository.save(tailor);
        sseNotifier.send(saved.getAnalysisJobId(), SseNotifier.QUESTIONS_WAITING,
                InterviewReadyResponse.waiting(interview.getInterviewId()));

        // 기다리기로 정하는 사이 사이클이 끝났으면 콜백은 이 건을 보지 못하고 지나갔다. 여기서 이어간다.
        if (questionPoolService.settled(interview.getUserId(), interview.getMode())) {
            return resumeWaiting(saved);
        }
        return saved;
    }

    /**
     * N:1 질문 구성 요청.
     *
     * <p>1:1과 달리 두 가지가 한 번에 돈다 — 기술 면접관이 쓸 원질문을 다시 쓰고, 나머지 면접관
     * 몫의 질문을 새로 만든다. 신규 질문의 근거는 프로젝트 요약뿐이라 그것까지 실어 보낸다.
     *
     * <p>기술 면접관 몫은 꺼낸 세트 하나({@link QuestionPoolService#setSize} 문항)다.
     * 나머지 면접관은 한 명당 {@link #OTHER_QUESTION_COUNT} 문항씩 맡으므로, 면접 길이는 면접관 수를
     * 따라간다 — 면접관 2·3·4명이 각각 네·여섯·여덟 문항이다. 인원 범위는
     * {@link MultiInterviewPanel}에 있다.
     */
    private QuestionTailorAcceptedResponse requestMultiTailor(InterviewEntity interview, String major,
                                                              QuestionPoolService.DrawnSet drawn) {
        List<PersonaEntity> members = orderedPersonas(interview.getInterviewId());
        PersonaEntity tech = members.getFirst();
        List<PersonaEntity> others = members.subList(1, members.size());

        List<TailoredQuestionResponse> techQuestions = drawn.questions();
        verifyTechQuestions(techQuestions);

        return aiServerClient.tailorQuestionsMulti(QuestionTailorMultiRequest.builder()
                .interviewId(String.valueOf(interview.getInterviewId()))
                .userId(String.valueOf(interview.getUserId()))
                .jobRole(resolveJobRole(major, tech))
                // persona.career는 면접관 설정이지 지원자 경력이 아니다. 지원자 경력은 아직 수집하지 않는다.
                .experienceLevel(null)
                .techPersona(toRequestPersona(tech, techQuestions.size()))
                .otherPersonas(others.stream()
                        .map(persona -> toRequestPersona(persona, OTHER_QUESTION_COUNT))
                        .toList())
                .questions(techQuestions.stream().map(this::toMultiRequestQuestion).toList())
                .projectSummary(toRequestProjectSummary(drawn))
                .callbackUrl(callbackBaseUrl + MULTI_CALLBACK_PATH)
                .build());
    }

    /**
     * 면접 진행 순서대로 정리한 면접관.
     *
     * <p>맨 앞은 반드시 기술 면접관이고, 뒤에 다른 직책이 한 명 이상 붙는다. 원질문을 맡을 자리가
     * 기술 면접관뿐이라 그가 없으면 요청을 만들 수 없고, 뒤가 비면 신규 질문을 맡을 면접관이 없어
     * N:1이 성립하지 않는다. 면접 생성에서 이미 걸러지지만 그 사이에 면접관이 지워질 수 있어
     * 여기서도 확인한다.
     *
     * <p>인원 상한도 여기서 다시 본다. 상한은 면접을 만들 때만 보므로, 상한을 좁히기 전에 열린
     * 면접은 그 시절 인원을 그대로 들고 남아 있다. 그대로 보내면 분석 서버가 422로 돌려주는데,
     * 그 실패는 면접 시작을 누르고 한참 뒤에야, 그것도 우리 말이 아닌 형태로 돌아온다. 여기서
     * 막아 무엇을 해야 하는지까지 함께 알린다.
     */
    private List<PersonaEntity> orderedPersonas(Long interviewId) {
        List<Long> personaIds = interviewPersonaRepository
                .findAllByInterviewIdOrderByPersonaOrderAsc(interviewId).stream()
                .map(InterviewPersonaEntity::getPersonaId)
                .toList();

        Map<Long, PersonaEntity> found = personaRepository.findAllById(personaIds).stream()
                .collect(Collectors.toMap(PersonaEntity::getPersonaId, Function.identity()));

        List<PersonaEntity> ordered = new ArrayList<>();
        for (Long personaId : personaIds) {
            PersonaEntity persona = found.get(personaId);
            if (persona == null) {
                throw BusinessException.notFound("면접관을 찾을 수 없습니다: " + personaId);
            }
            ordered.add(persona);
        }

        if (ordered.size() < 2 || ordered.getFirst().getRole() != Role.TECH) {
            throw BusinessException.unprocessable("N:1 면접의 면접관 구성이 올바르지 않습니다.");
        }
        // 맨 앞이 기술 면접관이고 직책은 겹칠 수 없으므로, 나머지가 그대로 기술 외 인원이다.
        if (!MultiInterviewPanel.isOtherCountAllowed(ordered.size() - 1)) {
            throw BusinessException.unprocessable("면접관이 " + ordered.size() + "명이라 질문을 준비할 수 없습니다. "
                    + "면접관을 " + MultiInterviewPanel.MAX_TOTAL_COUNT + "명 이하로 골라 면접을 새로 만들어 주세요.");
        }
        return ordered;
    }

    private QuestionTailorMultiRequest.Persona toRequestPersona(PersonaEntity persona, int questionCount) {
        return QuestionTailorMultiRequest.Persona.builder()
                .personaId(String.valueOf(persona.getPersonaId()))
                .role(persona.getRole() == null ? null : persona.getRole().name())
                .style(enumName(persona.getType()))
                .tone(enumName(persona.getTone()))
                .questionCount(questionCount)
                .build();
    }

    /**
     * 기술 면접관에게 넘길 원질문. 분석 서버는 넷 중 하나라도 비면 요청 전체를 422로 거부한다.
     *
     * <p>특히 기대 답변은 재작성 후에도 그대로 확인할 수 있어야 하는 기준값이라 비면 안 된다.
     * 콜백까지 갔다 오면 면접 시작이 그만큼 늦어지므로 요청 전에 막는다.
     */
    private void verifyTechQuestions(List<TailoredQuestionResponse> questions) {
        if (questions.isEmpty()) {
            throw BusinessException.unprocessable("기술 면접관에게 넘길 원질문이 없습니다.");
        }
        for (TailoredQuestionResponse question : questions) {
            if (question.getId() == null
                    || isBlank(question.getCategory())
                    || isBlank(question.getQuestion())
                    || isBlank(question.getExpectedAnswer())) {
                throw BusinessException.unprocessable(
                        "질문을 만들 재료가 모자랍니다. 포트폴리오 분석을 다시 진행해주세요.");
            }
        }
    }

    private QuestionTailorMultiRequest.Question toMultiRequestQuestion(TailoredQuestionResponse question) {
        return QuestionTailorMultiRequest.Question.builder()
                .id(question.getId())
                .category(question.getCategory())
                .question(question.getQuestion())
                .intention(question.getIntention())
                .expectedAnswer(question.getExpectedAnswer())
                .basedOn(question.getBasedOn())
                .build();
    }

    /**
     * 신규 질문의 유일한 근거.
     *
     * <p>종합 데이터 결과에 통째로 저장해둔 값이라 형태를 우리가 못 박아둘 수 없다. 여기서 해석하다
     * 실패하면 N:1을 열 수 없다는 뜻이므로 그렇게 알린다 — 500으로 나가면 사용자는 서버가
     * 고장난 것인지 분석을 다시 해야 하는 것인지 구분할 수 없다.
     *
     * <p>해석은 이 자리에서만 한다. 세트를 꺼내는 경로는 1:1 면접 시작도 함께 지나므로,
     * 그쪽에서 이 값을 건드리면 N:1에만 필요한 해석 때문에 1:1까지 멈춘다.
     */
    private QuestionTailorMultiRequest.ProjectSummary toRequestProjectSummary(QuestionPoolService.DrawnSet drawn) {
        Object raw = drawn.projectSummary();
        ProjectSummaryResponse summary;
        try {
            summary = objectMapper.convertValue(raw, ProjectSummaryResponse.class);
        } catch (IllegalArgumentException | JacksonException e) {
            log.warn("분석 결과의 프로젝트 요약을 해석하지 못했습니다. analysisJobId={}, 받은 키={}",
                    drawn.profileJobId(), describeShape(raw), e);
            throw BusinessException.unprocessable(
                    "프로젝트 요약을 읽지 못했습니다. 포트폴리오 분석을 다시 진행해주세요.");
        }

        if (summary == null || isBlank(summary.getOverview())) {
            // 여기까지 왔다는 것은 같은 종합 데이터로 세트를 이미 꺼냈다는 뜻이다. 분석은 끝나
            // 있고 요약만 비어 있으니, 안내도 "먼저 분석하라"가 아니라 그 사실을 가리켜야 한다.
            //
            // 어느 작업의 결과였는지와 실제로 도착한 키를 함께 남긴다. 이름이 어긋나면 값은 조용히
            // 비므로, 이 둘이 없으면 요약이 없는 것인지 이름이 다른 것인지 로그만으로는 가릴 수 없다.
            log.warn("N:1 질문을 만들 프로젝트 요약이 비어 있습니다. analysisJobId={}, 요약읽힘={}, 받은 키={}",
                    drawn.profileJobId(), summary != null, describeShape(raw));
            throw BusinessException.unprocessable(
                    "분석 결과에 프로젝트 요약이 없어 N:1 면접을 열 수 없습니다. 포트폴리오 분석을 다시 진행해주세요.");
        }

        return QuestionTailorMultiRequest.ProjectSummary.builder()
                .overview(summary.getOverview())
                // 항목 하나에 빈 칸이 있으면 분석 서버가 요약 전체를 거부한다. 그 한 건만 빼고 넘긴다 —
                // 근거가 조금 줄어들 뿐이지만, 통째로 거부되면 질문이 아예 만들어지지 않는다.
                .repositories(summary.getRepositories() == null ? List.of() : summary.getRepositories().stream()
                        .filter(repository -> !isBlank(repository.getRepo())
                                && !isBlank(repository.getRole())
                                && !isBlank(repository.getDescription()))
                        .map(repository -> QuestionTailorMultiRequest.Repository.builder()
                                .repo(repository.getRepo())
                                .role(repository.getRole())
                                .description(repository.getDescription())
                                .build())
                        .toList())
                .coreFeatures(summary.getCoreFeatures() == null ? List.of() : summary.getCoreFeatures().stream()
                        .filter(feature -> !isBlank(feature.getName()) && !isBlank(feature.getDescription()))
                        .map(feature -> QuestionTailorMultiRequest.CoreFeature.builder()
                                .name(feature.getName())
                                .description(feature.getDescription())
                                .basedOn(feature.getBasedOn())
                                .build())
                        .toList())
                .techStack(summary.getTechStack() == null ? List.of() : summary.getTechStack())
                .build();
    }

    /**
     * 재작성 개인화 축. 면접 설정에서 고른 면접관의 전공이 먼저고, 없으면 사용자 전공을 쓴다.
     *
     * <p>사용자 전공은 회원가입 때 고정값으로 들어간 것이라 사용자가 고른 적이 없다. 이것을 먼저
     * 보면 면접 설정에서 프론트엔드를 골라도 질문은 백엔드 기준으로 다시 쓰인다.
     */
    private String resolveJobRole(String userMajor, PersonaEntity persona) {
        if (persona != null && persona.getMajor() != null) {
            return persona.getMajor().name();
        }
        return normalizeMajor(userMajor);
    }

    /** 인증 서버는 MAJOR_BACKEND 형식으로 내려준다. 면접관 전공과 같은 BACKEND 형식으로 맞춘다. */
    private String normalizeMajor(String major) {
        String value = blankToNull(major);
        if (value == null) {
            return null;
        }
        return value.startsWith(AUTH_MAJOR_PREFIX) ? value.substring(AUTH_MAJOR_PREFIX.length()) : value;
    }

    private QuestionTailorRequest.Question toRequestQuestion(TailoredQuestionResponse question) {
        return QuestionTailorRequest.Question.builder()
                .id(question.getId())
                .category(question.getCategory())
                .question(question.getQuestion())
                .intention(question.getIntention())
                .expectedAnswer(question.getExpectedAnswer())
                .basedOn(question.getBasedOn())
                .build();
    }

    /**
     * 세 축이 모두 비면 분석 서버가 실패 콜백(422)을 보낸다.
     * 콜백까지 갔다 오면 면접 시작이 그만큼 늦어지므로 요청 전에 막는다.
     */
    private QuestionTailorRequest.Profile resolveProfile(String userMajor, Long personaId) {
        PersonaEntity persona = personaRepository.findById(personaId).orElse(null);

        String jobRole = resolveJobRole(userMajor, persona);
        String personaType = persona == null ? null : enumName(persona.getType());
        String personaTone = persona == null ? null : enumName(persona.getTone());

        if (jobRole == null && personaType == null) {
            throw BusinessException.unprocessable("질문을 다시 쓸 사전 정보가 없습니다.");
        }

        return QuestionTailorRequest.Profile.builder()
                .jobRole(jobRole)
                // persona.career는 면접관 설정이지 지원자 경력이 아니다. 지원자 경력은 아직 수집하지 않는다.
                .experienceLevel(null)
                .personaType(personaType)
                .personaTone(personaTone)
                .build();
    }

    /**
     * 콜백을 기다리다 시간이 지난 준비 건을 걷어낸다.
     *
     * <p>판정이 요청 스레드에만 있으면, 폴링하지 않고 SSE만 기다리는 클라이언트는 이미 가망이
     * 없는 작업을 구독 타임아웃까지 기다린다. 준비 제한 시간은 2분인데 구독은 15분이라 그
     * 차이만큼 아무 소식 없이 매달려 있게 된다. 그래서 요청과 무관하게 주기적으로도 훑는다.
     *
     * <p>한 건이 걸려 넘어져도 나머지는 정리한다. 실패한 건 하나 때문에 스윕이 통째로 멈추면
     * 그 뒤의 모든 면접이 같은 방식으로 매달린다.
     */
    @Scheduled(fixedDelayString = "${app.question-tailor.sweep-interval:30s}")
    public void sweepTimedOutPreparations() {
        LocalDateTime now = LocalDateTime.now();
        List<QuestionTailorEntity> stale = new ArrayList<>(questionTailorRepository.findAllByStatusAndCreatedAtBefore(
                TailorStatus.PENDING, now.minus(pendingTimeout)));
        stale.addAll(questionTailorRepository.findAllByStatusAndCreatedAtBefore(
                TailorStatus.WAITING, now.minus(waitingTimeout)));

        for (QuestionTailorEntity tailor : stale) {
            try {
                if (expire(tailor)) {
                    completePreparation(tailor);
                }
            } catch (RuntimeException e) {
                log.error("시간이 지난 질문 준비 건을 정리하지 못했습니다. tailorId={}, interviewId={}",
                        tailor.getTailorId(), tailor.getInterviewId(), e);
            }
        }
    }

    /**
     * 분석 서버는 콜백 전송에 실패하면 결과를 폐기한다.
     * 그 경우 콜백이 영영 오지 않으므로, 오래 걸린 PENDING은 실패로 정리한다.
     */
    private boolean expireIfTimedOut(QuestionTailorEntity tailor) {
        Duration timeout = tailor.getStatus() == TailorStatus.PENDING ? pendingTimeout
                : tailor.getStatus() == TailorStatus.WAITING ? waitingTimeout
                : null;
        if (timeout == null || tailor.getCreatedAt() == null) {
            return false;
        }
        if (tailor.getCreatedAt().plus(timeout).isAfter(LocalDateTime.now())) {
            return false;
        }
        return expire(tailor);
    }

    /**
     * 시간이 지난 건을 실패로 닫는다. 차지한 쪽만 참을 돌려받는다.
     *
     * <p>스윕과 조회가 같은 건을 동시에 집어들 수 있다. 읽어둔 값만 보고 판단하면 둘 다
     * 실패로 닫고 각자 알려, 같은 실패가 두 번 나간다.
     */
    private boolean expire(QuestionTailorEntity tailor) {
        if (tailor.getStatus() == TailorStatus.WAITING) {
            log.warn("새 질문 사이클이 {} 내에 도착하지 않아 실패 처리합니다. tailorId={}, interviewId={}",
                    waitingTimeout, tailor.getTailorId(), tailor.getInterviewId());
            return closeWaiting(tailor, "새 질문을 제때 준비하지 못했습니다. 잠시 후 다시 시도해주세요.");
        }
        if (questionTailorRepository.claimExpiration(tailor.getTailorId()) == 0) {
            return false;
        }

        if (tailor.getMode() == InterviewMode.MULTI) {
            log.warn("N:1 질문 구성 콜백이 {} 내에 도착하지 않아 실패 처리합니다. tailorId={}, jobId={}",
                    pendingTimeout, tailor.getTailorId(), tailor.getJobId());

            failWithoutFallback(tailor, "질문을 준비하지 못했습니다. 잠시 후 다시 시도해주세요.");
            questionTailorRepository.save(tailor);
            return true;
        }

        log.warn("질문 재작성 콜백이 {} 내에 도착하지 않아 원질문으로 진행합니다. tailorId={}, jobId={}",
                pendingTimeout, tailor.getTailorId(), tailor.getJobId());

        fallbackToOriginal(tailor, "질문 재작성 결과를 제때 받지 못해 원질문으로 진행합니다.");
        questionTailorRepository.save(tailor);
        return true;
    }

    /**
     * 확정된 준비 건의 남은 뒷단을 밟는다.
     *
     * <p>넘길 질문이 있으면 채팅 서버로 넘기고, 없으면 실패를 알린다. 알리지 않고 넘어가면
     * 구독은 아무것도 받지 못한 채 남고, 웹은 준비가 끝나기를 기다리는 화면에 머문다.
     *
     * <p>같은 실패를 여러 번 알려도 안전하다. 구독은 이벤트 이름마다 한 번만 흘려보내고,
     * 흘려보낸 뒤에는 닫힌다. 뒤늦게 붙은 구독은 그때 다시 되짚어 받는다.
     */
    private void completePreparation(QuestionTailorEntity tailor) {
        if (isOpen(tailor)) {
            return;
        }
        // 폴백 없이 실패한 N:1은 넘길 질문 자체가 없다. 1:1 실패는 원질문이 들어차 있어 여기 걸리지 않는다.
        if (tailor.getQuestions() == null || tailor.getQuestions().isEmpty()) {
            notifyPreparationFailed(tailor, FailureStage.QUESTION_GENERATION);
            return;
        }
        deliverToChatServer(tailor);
    }

    /** 아직 질문이 확정되지 않았다. 재작성 콜백을 기다리거나 새 질문 사이클을 기다리는 중이다. */
    private boolean isOpen(QuestionTailorEntity tailor) {
        return tailor.getStatus() == TailorStatus.PENDING || tailor.getStatus() == TailorStatus.WAITING;
    }

    /**
     * 분석 서버가 질문 사이클을 만들고 보내는 콜백. 그 사이클을 기다리던 면접 준비가 있으면 이어간다.
     *
     * <p>실패했는데 기다리는 준비가 있으면 한 번 더 요청한다. 그마저 실패하면 기다리던 준비를 실패로 알린다.
     * 기다리는 준비가 없으면 실패로만 남긴다 — 다음 면접을 시작할 때 다시 요청한다.
     *
     * <p>준비 한 건이 걸려 넘어져도 나머지는 이어간다. 여기서 예외가 나면 분석 서버가 콜백을 다시 보내도
     * 사이클은 이미 반영돼 아무도 이어주지 않는다.
     */
    public void handleCycleCallback(QuestionCycleCallbackRequest request, Long cycleId) {
        QuestionPoolService.CycleOutcome outcome = questionPoolService.applyCycleResult(request, cycleId);
        if (outcome == null) {
            return;
        }
        List<QuestionTailorEntity> waiting = questionTailorRepository
                .findAllByUserIdAndModeAndStatus(outcome.userId(), outcome.mode(), TailorStatus.WAITING);
        if (waiting.isEmpty()) {
            return;
        }
        if (!outcome.succeeded() && questionPoolService.retryForWaiting(outcome.cycleId())) {
            return;
        }

        for (QuestionTailorEntity tailor : waiting) {
            try {
                if (outcome.succeeded()) {
                    resumeWaiting(tailor);
                } else if (closeWaiting(tailor, "새 질문을 준비하지 못했습니다. 잠시 후 다시 시도해주세요.")) {
                    completePreparation(tailor);
                }
            } catch (RuntimeException e) {
                log.error("질문 사이클을 기다리던 면접 준비를 처리하지 못했습니다. tailorId={}, interviewId={}",
                        tailor.getTailorId(), tailor.getInterviewId(), e);
            }
        }
    }

    /**
     * 사이클을 기다리던 준비를 이어간다. 차지한 쪽만 진행한다 — 콜백 재전송과 시간 초과 정리가 겹칠 수 있다.
     *
     * <p>차지하면서 시작 시각을 지금으로 옮긴다. 기다린 시간까지 재작성 제한 시간에 넣으면 재작성 요청을
     * 보내자마자 시간 초과로 걷힌다.
     *
     * <p>이어가다 실패하면 준비 실패로 알린다. 웹은 기다림 안내를 받은 채 결과를 기다리고 있다.
     */
    private QuestionTailorEntity resumeWaiting(QuestionTailorEntity tailor) {
        if (questionTailorRepository.claimResume(tailor.getTailorId(), LocalDateTime.now()) == 0) {
            return tailor;
        }
        tailor.setStatus(TailorStatus.PENDING);
        try {
            InterviewEntity interview = interviewRepository.findById(tailor.getInterviewId())
                    .orElseThrow(() -> BusinessException.notFound("면접을 찾을 수 없습니다"));
            // 콜백에는 사용자 정보가 없다. 재작성 축의 전공은 종합 데이터에 실었던 값으로 대신한다.
            return startTailor(interview, null, tailor);
        } catch (RuntimeException e) {
            log.error("질문 사이클을 받았지만 면접 준비를 이어가지 못했습니다. tailorId={}, interviewId={}",
                    tailor.getTailorId(), tailor.getInterviewId(), e);
            failWithoutFallback(tailor, "질문을 준비하지 못했습니다. 잠시 후 다시 시도해주세요.");
            QuestionTailorEntity saved = questionTailorRepository.save(tailor);
            notifyPreparationFailed(saved, FailureStage.QUESTION_GENERATION);
            return saved;
        }
    }

    /** 기다리던 준비를 실패로 닫는다. 차지한 쪽만 참을 돌려받는다. 알리는 일은 부르는 쪽이 한다. */
    private boolean closeWaiting(QuestionTailorEntity tailor, String errorMessage) {
        if (questionTailorRepository.claimExpiration(tailor.getTailorId()) == 0) {
            return false;
        }
        failWithoutFallback(tailor, errorMessage);
        questionTailorRepository.save(tailor);
        return true;
    }

    /**
     * 분석 서버가 재작성을 마치고 보내는 콜백.
     * 재작성 질문을 원질문과 함께 저장한 뒤, 그 전체를 채팅 서버로 넘긴다.
     * 재전송이 있을 수 있어 두 번 받아도 안전해야 한다.
     *
     * <p>저장과 채팅 서버 호출을 한 트랜잭션으로 묶지 않는다. 외부 호출이 늦어져도 재작성 결과는
     * 이미 DB에 남아 있어야 조회와 재전달이 가능하다.
     */
    public void handleCallback(QuestionTailorCallbackRequest request) {
        QuestionTailorEntity tailor = findTarget(request);
        if (tailor == null) {
            log.warn("알 수 없는 질문 재작성 콜백을 받았습니다. jobId={}, interviewId={}",
                    request.getJobId(), request.getInterviewId());
            return;
        }

        if (tailor.getJobId() == null) {
            tailor.setJobId(request.getJobId());
        }

        if (STATUS_SUCCEEDED.equalsIgnoreCase(request.getStatus()) && request.getResult() != null) {
            applySuccess(tailor, request.getResult());
        } else {
            applyFailure(tailor, request.getError());
        }

        questionTailorRepository.save(tailor);
        completePreparation(tailor);
    }

    /**
     * 재작성이 확정된 면접 데이터를 채팅 서버로 넘긴다.
     *
     * <p>아직 PENDING이면 넘길 최종 질문이 없으므로 넘어간다. 이미 넘긴 건은 콜백이 재전송돼도
     * 다시 넘기지 않는다. 전달에 실패해도 콜백 자체는 성공 처리한다 — 여기서 예외를 던지면
     * 분석 서버가 결과를 재전송하다 폐기해버려 재작성본까지 잃는다.
     *
     * <p>이 메서드는 콜백과 면접 시작, 준비 상태 조회 세 곳에서 불리고 모두 트랜잭션 밖이다.
     * 읽어둔 값만 보고 판단하면 콜백이 넘기는 중에 들어온 조회가 한 번 더 넘겨, 채팅 서버에
     * 같은 면접을 여는 요청이 두 번 도착한다. 그래서 넘기기 전에 권리를 차지하고, 차지한
     * 쪽만 넘긴다.
     */
    private void deliverToChatServer(QuestionTailorEntity tailor) {
        if (isOpen(tailor) || Boolean.TRUE.equals(tailor.getChatDelivered())) {
            return;
        }
        // 폴백 없이 실패한 N:1은 넘길 질문 자체가 없다. 1:1 실패는 원질문이 들어차 있어 여기 걸리지 않는다.
        if (tailor.getQuestions() == null || tailor.getQuestions().isEmpty()) {
            return;
        }

        // 차지하지 못했다면 다른 쪽이 이미 넘겼거나 넘기는 중이다. 그 결과는 다음 조회에서 읽힌다.
        if (questionTailorRepository.claimChatDelivery(tailor.getTailorId()) == 0) {
            return;
        }

        boolean delivered;
        try {
            chatInterviewHandoffService.deliver(tailor);
            tailor.setChatDelivered(true);
            tailor.setChatErrorMessage(null);
            delivered = true;
        } catch (RuntimeException e) {
            log.error("면접 데이터를 채팅 서버로 넘기지 못했습니다. tailorId={}, interviewId={}",
                    tailor.getTailorId(), tailor.getInterviewId(), e);
            // 차지했던 것을 놓아준다. 그대로 두면 넘어간 적이 없는데도 넘긴 것으로 남아 다시 시도하지 못한다.
            tailor.setChatDelivered(false);
            tailor.setChatErrorMessage(e.getMessage());
            delivered = false;
        }
        questionTailorRepository.save(tailor);

        // 저장이 끝난 뒤에 알린다. 웹은 이 이벤트를 받고 곧바로 상태를 조회하므로, 먼저 보내면
        // 아직 저장되지 않은 것을 조회하게 된다.
        notifySubscriber(tailor, delivered);
    }

    /**
     * 구독 중인 웹에 면접 준비가 끝났음을 알린다.
     *
     * <p>웹은 분석 jobId 하나로 구독한 채 면접관을 고르고 면접 시작까지 진행한다. 그 구독을
     * 되찾는 열쇠가 재작성 건에 남겨둔 분석 jobId다.
     *
     * <p>1:1 재작성이 실패했어도 준비 완료로 알린다. 그때는 원질문이 폴백으로 들어가 면접이
     * 그대로 열리기 때문이다. 이 자리에서 못 여는 것은 채팅 서버에 면접을 열지 못했을 때뿐이고,
     * 만들 질문 자체가 없어 멈춘 N:1은 {@link #completePreparation}이 따로 알린다.
     */
    private void notifySubscriber(QuestionTailorEntity tailor, boolean delivered) {
        if (!delivered) {
            notifyPreparationFailed(tailor, FailureStage.CHAT_DELIVERY);
            return;
        }

        String analysisJobId = tailor.getAnalysisJobId();
        if (analysisJobId == null) {
            // 어느 분석에서 비롯됐는지 모르면 되찾을 구독도 없다. 웹은 조회로 확인해야 한다.
            log.warn("분석 작업을 알 수 없어 면접 준비 완료를 알리지 못했습니다. tailorId={}", tailor.getTailorId());
            return;
        }
        sseNotifier.sendFinal(analysisJobId, SseNotifier.INTERVIEW_READY, toReady(tailor));
    }

    /**
     * 면접을 열지 못했음을 구독에 알린다.
     *
     * <p>어느 단계에서 멈췄는지를 함께 싣는다. 단계가 없으면 웹은 질문부터 다시 만들어야
     * 하는지 전달만 다시 하면 되는지 모른 채 같은 안내를 보여주게 된다.
     */
    private void notifyPreparationFailed(QuestionTailorEntity tailor, FailureStage stage) {
        String analysisJobId = tailor.getAnalysisJobId();
        if (analysisJobId == null) {
            log.warn("분석 작업을 알 수 없어 면접 준비 실패를 알리지 못했습니다. tailorId={}, 단계={}",
                    tailor.getTailorId(), stage);
            return;
        }
        sseNotifier.sendFinal(analysisJobId, SseNotifier.INTERVIEW_PREPARATION_FAILED,
                InterviewReadyResponse.failed(tailor.getInterviewId(), stage, failureMessage(tailor, stage)));
    }

    private String failureMessage(QuestionTailorEntity tailor, FailureStage stage) {
        return stage == FailureStage.CHAT_DELIVERY ? tailor.getChatErrorMessage() : tailor.getErrorMessage();
    }

    /**
     * 되짚어 보낼 이벤트 한 건.
     *
     * @param eventName {@link SseNotifier#INTERVIEW_READY} 또는
     *                  {@link SseNotifier#INTERVIEW_PREPARATION_FAILED}
     */
    public record PreparationEvent(String eventName, InterviewReadyResponse payload) {
        /** 흐름이 끝나는 이벤트인지. 질문 대기 안내는 준비가 이어지므로 구독을 닫지 않는다. */
        public boolean last() {
            return !SseNotifier.QUESTIONS_WAITING.equals(eventName);
        }
    }

    /**
     * 뒤늦게 붙은 구독에 되짚어줄 면접 준비 결과. 아직 준비 중이면 아무것도 돌려주지 않는다.
     *
     * <p>성공만 되짚으면 실패한 준비를 구독한 클라이언트는 아무것도 받지 못한 채 타임아웃까지
     * 매달린다. SSE가 유실되든 클라이언트가 뒤늦게 붙든 같은 최종 상태에 닿아야 한다.
     *
     * <p>넘기는 중인 건은 준비된 것으로 보지 않는다. 채팅 서버 전달은 권리를 먼저 차지하고
     * 시작하므로, 그 사이에 조회하면 아직 열리지도 않은 면접을 열렸다고 알리게 된다.
     */
    @Transactional(readOnly = true)
    public PreparationEvent findPreparationEvent(String analysisJobId) {
        if (analysisJobId == null) {
            return null;
        }
        QuestionTailorEntity tailor = questionTailorRepository
                .findTopByAnalysisJobIdOrderByCreatedAtDesc(analysisJobId)
                .orElse(null);

        if (tailor != null && tailor.getStatus() == TailorStatus.WAITING) {
            return new PreparationEvent(SseNotifier.QUESTIONS_WAITING, InterviewReadyResponse.waiting(tailor.getInterviewId()));
        }
        PreparationState state = PreparationState.of(tailor);
        if (state.status() == PreparationStatus.READY) {
            return new PreparationEvent(SseNotifier.INTERVIEW_READY, toReady(tailor));
        }
        if (state.status() == PreparationStatus.FAILED) {
            return new PreparationEvent(SseNotifier.INTERVIEW_PREPARATION_FAILED,
                    InterviewReadyResponse.failed(tailor.getInterviewId(), state.failureStage(),
                            failureMessage(tailor, state.failureStage())));
        }
        return null;
    }

    private InterviewReadyResponse toReady(QuestionTailorEntity tailor) {
        String sessionId = interviewRepository.findById(tailor.getInterviewId())
                .map(InterviewEntity::getSessionId)
                .orElse(null);
        return InterviewReadyResponse.ready(tailor.getInterviewId(), sessionId, tailor.getTailored());
    }

    /**
     * 분석 서버가 N:1 질문 구성을 마치고 보내는 콜백.
     *
     * <p>1:1과 달리 폴백이 없다. 기술 외 면접관 몫의 신규 질문은 여기서 받은 값이 유일한 원본이라,
     * 실패하면 면접에 쓸 질문이 남지 않는다. 그 경우 채팅 서버로 넘기지 않고 실패로 남긴다 —
     * 반쪽짜리로 넘기면 기술 질문 {@link QuestionPoolService#setSize}개짜리 면접이 N:1인 척 열린다.
     */
    public void handleMultiCallback(QuestionTailorMultiCallbackRequest request) {
        QuestionTailorEntity tailor = findMultiTarget(request);
        if (tailor == null) {
            log.warn("알 수 없는 N:1 질문 구성 콜백을 받았습니다. jobId={}, interviewId={}",
                    request.getJobId(), request.getInterviewId());
            return;
        }

        if (tailor.getJobId() == null) {
            tailor.setJobId(request.getJobId());
        }

        if (STATUS_SUCCEEDED.equalsIgnoreCase(request.getStatus()) && request.getResult() != null) {
            applyMultiSuccess(tailor, request.getResult());
        } else {
            QuestionTailorMultiCallbackRequest.Error error = request.getError();
            tailor.setErrorStatusCode(error == null ? null : error.getStatusCode());
            failWithoutFallback(tailor, error == null || error.getMessage() == null
                    ? "질문을 준비하지 못했습니다. 잠시 후 다시 시도해주세요."
                    : error.getMessage());
        }

        questionTailorRepository.save(tailor);
        completePreparation(tailor);
    }

    private void applyMultiSuccess(QuestionTailorEntity tailor, QuestionTailorMultiCallbackRequest.Result result) {
        List<QuestionTailorMultiCallbackRequest.Question> questions =
                result.getQuestions() == null ? List.of() : result.getQuestions();

        // 기술 질문의 채점 기준은 우리가 넘긴 값이다. 콜백에 빠져 오면 원질문에서 되찾는다.
        Map<Integer, String> sourceIntentions = new LinkedHashMap<>();
        originalQuestions(tailor).forEach(source -> sourceIntentions.put(source.getId(), source.getIntention()));

        List<TailoredQuestionResponse> prepared = new ArrayList<>();
        for (QuestionTailorMultiCallbackRequest.Question question : questions) {
            if (question.getId() == null || question.getQuestion() == null || question.getQuestion().isBlank()) {
                continue;
            }
            prepared.add(TailoredQuestionResponse.builder()
                    .id(question.getId())
                    .personaId(question.getPersonaId())
                    .category(question.getCategory())
                    .question(question.getQuestion())
                    // 신규 질문의 채점 기준은 여기서 받은 이 값뿐이다. 버리면 되찾을 데가 없다.
                    .intention(question.getIntention() != null
                            ? question.getIntention()
                            : sourceIntentions.get(question.getId()))
                    .expectedAnswer(question.getExpectedAnswer())
                    .basedOn(question.getBasedOn())
                    .build());
        }

        if (prepared.isEmpty()) {
            log.warn("N:1 질문 구성 결과에 쓸 수 있는 질문이 없습니다. tailorId={}", tailor.getTailorId());
            failWithoutFallback(tailor, "질문을 준비하지 못했습니다. 잠시 후 다시 시도해주세요.");
            return;
        }

        tailor.setStatus(TailorStatus.SUCCEEDED);
        tailor.setTailored(true);
        tailor.setErrorStatusCode(null);
        tailor.setErrorMessage(null);
        // 배열 순서가 그대로 면접 진행 순서다. 다시 정렬하지 않는다.
        tailor.setQuestions(prepared);
    }

    private QuestionTailorEntity findMultiTarget(QuestionTailorMultiCallbackRequest request) {
        if (request.getJobId() != null) {
            QuestionTailorEntity byJobId = questionTailorRepository.findByJobId(request.getJobId()).orElse(null);
            if (byJobId != null) {
                return byJobId;
            }
        }
        Long interviewId = parseInterviewId(request.getInterviewId());
        if (interviewId == null) {
            return null;
        }
        return questionTailorRepository.findTopByInterviewIdOrderByCreatedAtDesc(interviewId).orElse(null);
    }

    private QuestionTailorEntity findTarget(QuestionTailorCallbackRequest request) {
        if (request.getJobId() != null) {
            QuestionTailorEntity byJobId = questionTailorRepository.findByJobId(request.getJobId()).orElse(null);
            if (byJobId != null) {
                return byJobId;
            }
        }
        // 세션이 아직 없는 시점이라 매칭 키는 interviewId다.
        Long interviewId = parseInterviewId(request.getInterviewId());
        if (interviewId == null) {
            return null;
        }
        return questionTailorRepository.findTopByInterviewIdOrderByCreatedAtDesc(interviewId).orElse(null);
    }

    private Long parseInterviewId(String interviewId) {
        if (interviewId == null) {
            return null;
        }
        try {
            return Long.valueOf(interviewId);
        } catch (NumberFormatException e) {
            log.warn("콜백의 interviewId 형식이 올바르지 않습니다. interviewId={}", interviewId);
            return null;
        }
    }

    private void applySuccess(QuestionTailorEntity tailor, QuestionTailorCallbackRequest.Result result) {
        tailor.setStatus(TailorStatus.SUCCEEDED);
        tailor.setErrorStatusCode(null);
        tailor.setErrorMessage(null);

        List<TailoredQuestionResponse> source = originalQuestions(tailor);
        if (!Boolean.TRUE.equals(result.getTailored())) {
            // 분석 서버가 이미 원질문으로 폴백한 경우. 본문도 원문 그대로 실려온다.
            tailor.setTailored(false);
            tailor.setQuestions(source);
            return;
        }

        Map<Integer, String> rewritten = new LinkedHashMap<>();
        List<QuestionTailorCallbackRequest.Question> questions =
                result.getQuestions() == null ? List.of() : result.getQuestions();
        for (QuestionTailorCallbackRequest.Question question : questions) {
            if (question.getId() != null && question.getQuestion() != null && !question.getQuestion().isBlank()) {
                rewritten.put(question.getId(), question.getQuestion());
            }
        }

        // 재작성분과 원문이 한 면접에 섞이면 어조가 들쭉날쭉해진다. 하나라도 비면 전체를 원문으로 되돌린다.
        boolean complete = source.stream().allMatch(question -> rewritten.containsKey(question.getId()));
        if (!complete) {
            log.warn("재작성 결과에 누락된 질문이 있어 전체를 원질문으로 되돌립니다. tailorId={}, 원본={}건, 재작성={}건",
                    tailor.getTailorId(), source.size(), rewritten.size());
            tailor.setTailored(false);
            tailor.setQuestions(source);
            return;
        }

        tailor.setTailored(true);
        // 본문만 갈아끼운다. category/intention/expectedAnswer/basedOn은 콜백에 실려오지 않아 원질문 값을 유지한다.
        tailor.setQuestions(source.stream()
                .map(question -> TailoredQuestionResponse.builder()
                        .id(question.getId())
                        .category(question.getCategory())
                        .question(rewritten.get(question.getId()))
                        .intention(question.getIntention())
                        .expectedAnswer(question.getExpectedAnswer())
                        .basedOn(question.getBasedOn())
                        .build())
                .toList());
    }

    private void applyFailure(QuestionTailorEntity tailor, QuestionTailorCallbackRequest.Error error) {
        tailor.setErrorStatusCode(error == null ? null : error.getStatusCode());
        fallbackToOriginal(tailor, error == null
                ? "질문 재작성에 실패해 원질문으로 진행합니다."
                : error.getMessage());
    }

    /**
     * 폴백 없이 실패로 닫는다. N:1 전용이다.
     *
     * <p>기술 원질문 세트는 남아 있지만 그것만으로 면접을 열면 N:1이
     * 아니다. 기술 면접관을 뺀 나머지가 질문 없이 앉아 있게 되고, 사용자는 왜 그런지 알 길이
     * 없다. 열지 않는 편이 낫다.
     */
    private void failWithoutFallback(QuestionTailorEntity tailor, String errorMessage) {
        tailor.setStatus(TailorStatus.FAILED);
        tailor.setTailored(false);
        tailor.setQuestions(null);
        tailor.setErrorMessage(errorMessage);
    }

    // 재작성이 안 됐다고 면접을 못 열게 만드는 편이 더 손해다. 실패해도 원질문은 남긴다.
    private void fallbackToOriginal(QuestionTailorEntity tailor, String errorMessage) {
        tailor.setStatus(TailorStatus.FAILED);
        tailor.setTailored(false);
        tailor.setQuestions(originalQuestions(tailor));
        tailor.setErrorMessage(errorMessage);
    }

    private List<TailoredQuestionResponse> originalQuestions(QuestionTailorEntity tailor) {
        return tailor.getSourceQuestions() == null ? List.of() : tailor.getSourceQuestions();
    }

    /**
     * 면접 시작 뒤 클라이언트가 준비 상태를 확인하는 조회.
     * 재작성이 실패했어도 원질문을 돌려주므로 응답만 보고 면접을 열 수 있다.
     */
    public QuestionTailorResponse getTailorResult(Long userId, Long interviewId) {
        InterviewEntity interview = interviewRepository.findById(interviewId)
                .orElseThrow(() -> BusinessException.notFound("면접을 찾을 수 없습니다"));
        verifyOwner(interview.getUserId(), userId);

        QuestionTailorEntity tailor = questionTailorRepository
                .findTopByInterviewIdOrderByCreatedAtDesc(interviewId)
                .orElse(null);
        if (tailor == null) {
            // 세트는 면접을 시작할 때 꺼낸다. 그 전에는 어느 세트가 될지 정해지지 않았다.
            return QuestionTailorResponse.notRequested(interviewId, List.of());
        }

        // 폴링하는 클라이언트가 PENDING에 갇히지 않도록 조회 시점에도 판정하고, 밀린 뒷단을 마저 밟는다.
        expireIfTimedOut(tailor);
        completePreparation(tailor);

        return QuestionTailorResponse.of(tailor);
    }

    // 면접 질문은 본인만 볼 수 있어야 한다.
    private void verifyOwner(Long ownerId, Long requesterId) {
        if (!requesterId.equals(ownerId)) {
            throw BusinessException.forbidden("본인의 면접 질문만 다룰 수 있습니다.");
        }
    }

    private String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }

    private String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    /**
     * 요약 자리에 실제로 무엇이 왔는지 한 줄로 옮긴다.
     *
     * <p>값이 아니라 키만 남긴다. 이름이 어긋나 비었는지 값 자체가 없는지를 가리는 데는 키로
     * 충분하고, 요약 본문은 사용자가 올린 포트폴리오 내용이라 로그에 흘릴 것이 아니다.
     */
    private String describeShape(Object raw) {
        if (raw == null) {
            return "없음";
        }
        if (raw instanceof Map<?, ?> map) {
            return map.keySet().toString();
        }
        return raw.getClass().getSimpleName();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
