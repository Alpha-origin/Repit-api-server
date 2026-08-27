package repit.repit_api_server.domain.userdata.feedback.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import repit.repit_api_server.domain.userdata.feedback.dto.request.FeedbackCallbackRequest;
import repit.repit_api_server.domain.userdata.feedback.dto.request.FeedbackMultiRequest;
import repit.repit_api_server.domain.userdata.feedback.dto.request.FeedbackSoloRequest;
import repit.repit_api_server.domain.userdata.feedback.dto.response.FeedbackAcceptedResponse;
import repit.repit_api_server.domain.userdata.feedback.dto.response.FeedbackResponse;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackEntity;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackItemEntity;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackPersonaEntity;
import repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackStatus;
import repit.repit_api_server.domain.userdata.feedback.repository.FeedbackItemRepository;
import repit.repit_api_server.domain.userdata.feedback.repository.FeedbackPersonaRepository;
import repit.repit_api_server.domain.userdata.feedback.repository.FeedbackRepository;
import repit.repit_api_server.domain.userdata.answer.entity.AnswerEntity;
import repit.repit_api_server.domain.userdata.answer.repository.AnswerRepository;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewPersonaEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewPersonaRepository;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.persona.entity.PersonaEntity;
import repit.repit_api_server.domain.userdata.persona.repository.PersonaRepository;
import repit.repit_api_server.domain.userdata.question.entity.QuestionEntity;
import repit.repit_api_server.domain.userdata.question.entity.enums.Type;
import repit.repit_api_server.domain.userdata.question.repository.QuestionRepository;
import repit.repit_api_server.global.client.AiServerClient;
import repit.repit_api_server.global.client.AuthServerClient;
import repit.repit_api_server.global.exception.BusinessException;
import repit.repit_api_server.global.response.UserResponse;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FeedbackService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackService.class);

    // 1:1과 N:1이 같은 경로로 돌아온다. 콜백 본문은 personas 블록이 있고 없고만 다르다.
    private static final String CALLBACK_PATH = "/api/feedbacks/callback";
    private static final int MAX_QUESTIONS = 50;
    private static final String STATUS_SUCCEEDED = "succeeded";
    private static final String INTENTION_MISSING = "질문 의도가 기록되지 않았습니다.";

    private final FeedbackRepository feedbackRepository;
    private final FeedbackItemRepository feedbackItemRepository;
    private final FeedbackPersonaRepository feedbackPersonaRepository;
    private final InterviewRepository interviewRepository;
    private final InterviewPersonaRepository interviewPersonaRepository;
    private final PersonaRepository personaRepository;
    private final QuestionRepository questionRepository;
    private final AnswerRepository answerRepository;
    private final AiServerClient aiServerClient;
    private final AuthServerClient authServerClient;

    @Value("${app.callback-base-url}")
    private String callbackBaseUrl;

    // 이 시간을 넘도록 콜백이 오지 않으면 실패로 간주한다.
    @Value("${app.feedback.pending-timeout:5m}")
    private Duration pendingTimeout;


    // 외부 서버 호출이 세 번 들어가므로 트랜잭션으로 감싸지 않는다.
    // 감싸면 느린 HTTP 응답을 기다리는 내내 DB 커넥션을 붙잡게 된다.
    // 개별 저장은 각 리포지토리 호출이 자체 트랜잭션으로 처리한다.
    public FeedbackAcceptedResponse requestFeedback(String authorization, Long interviewId) {
        Long userId = currentUserId(authorization);

        InterviewEntity interview = findInterview(interviewId);
        verifyOwner(interview.getUserId(), userId);

        FeedbackStatus running = runningStatusOf(interviewId);
        if (running == FeedbackStatus.PENDING) {
            throw BusinessException.conflict("이미 피드백을 생성하고 있습니다. 잠시 후 다시 확인해주세요.");
        }
        if (running == FeedbackStatus.SUCCEEDED) {
            throw BusinessException.conflict("이미 생성된 피드백이 있습니다.");
        }

        return startFeedback(interview);
    }

    /**
     * 면접이 끝나 기록이 넘어온 직후 채점을 접수한다. 채팅 서버가 부르는 길이라 사용자 토큰이 없다.
     *
     * <p>채점 대상이 누구 것인지는 토큰이 아니라 면접에 적힌 소유자로 정한다. 면접을 시작할 때
     * 이미 본인 확인을 거쳤고, 여기까지 오는 요청은 채팅 서버가 보내는 서버 간 호출뿐이다.
     *
     * <p>중단된 면접도 그대로 채점한다. 답한 데까지는 평가할 것이 남아 있고, 웹이 직접 요청하던
     * 길도 상태를 가리지 않았다. 답변이 하나도 없는 면접은 {@code loadTranscript}가 막는다.
     *
     * <p>이미 채점 중이거나 끝난 면접은 건너뛴다. 웹이 먼저 요청했거나 같은 기록이 두 번 넘어온
     * 경우라, 여기서 거절로 취급하면 정상적인 흐름이 실패로 남는다.
     */
    public void requestFeedbackForFinishedInterview(Long interviewId) {
        InterviewEntity interview = findInterview(interviewId);

        FeedbackStatus running = runningStatusOf(interviewId);
        if (running != null) {
            log.info("이미 채점이 접수된 면접이라 자동 채점을 건너뜁니다. interviewId={}, status={}",
                    interviewId, running);
            return;
        }

        FeedbackAcceptedResponse accepted = startFeedback(interview);
        log.info("면접 기록을 받아 채점을 접수했습니다. interviewId={}, jobId={}",
                interviewId, accepted == null ? null : accepted.getJobId());
    }

    private InterviewEntity findInterview(Long interviewId) {
        return interviewRepository.findById(interviewId)
                .orElseThrow(() -> BusinessException.notFound("면접을 찾을 수 없습니다"));
    }

    /**
     * 이 면접에 이미 살아 있는 채점이 있는지. 없으면 null이다.
     *
     * <p>PENDING과 SUCCEEDED만 살아 있는 것으로 본다. FAILED는 다시 요청할 수 있어야 하고,
     * 콜백이 오지 않은 채 시간을 넘긴 PENDING도 여기서 실패로 정리해 다음 요청을 열어준다.
     */
    private FeedbackStatus runningStatusOf(Long interviewId) {
        FeedbackEntity existing = feedbackRepository.findTopByInterviewIdOrderByCreatedAtDesc(interviewId)
                .orElse(null);
        if (existing == null) {
            return null;
        }
        expireIfTimedOut(existing);
        if (existing.getStatus() == FeedbackStatus.PENDING || existing.getStatus() == FeedbackStatus.SUCCEEDED) {
            return existing.getStatus();
        }
        return null;
    }

    /** 분석 서버에 채점을 맡기고 접수 사실을 남긴다. 웹이 부르는 길과 채팅 서버가 부르는 길이 함께 쓴다. */
    private FeedbackAcceptedResponse startFeedback(InterviewEntity interview) {
        // N:1은 면접관별 평가까지 받아야 해서 엔드포인트가 다르다. 1:1 채점에 태우면
        // 질문이 누구 것인지 잃고 personas 블록도 오지 않는다.
        FeedbackAcceptedResponse accepted = interview.getMode() == InterviewMode.MULTI
                ? aiServerClient.requestMultiFeedback(toMultiRequest(interview))
                : aiServerClient.requestSoloFeedback(toSoloRequest(interview));

        feedbackRepository.save(FeedbackEntity.builder()
                .interviewId(interview.getInterviewId())
                .userId(interview.getUserId())
                .sessionId(interview.getSessionId())
                .jobId(accepted == null ? null : accepted.getJobId())
                .status(FeedbackStatus.PENDING)
                .build());

        return accepted;
    }

    /**
     * 채점에 실을 면접 기록. 질문과 답변, 그리고 질문 id 모음을 함께 들고 다닌다.
     *
     * <p>면접 내용은 채팅 서버가 아니라 우리 DB에서 읽는다. 채팅 서버는 면접이 끝나면 결과를
     * 이 서버로 넘기고 곧바로 세션을 지우므로, 피드백을 요청하는 시점에는 물어볼 상대가 없다.
     * 우리 DB가 그때부터 유일한 원본이다.
     */
    private record Transcript(List<QuestionEntity> questions, List<AnswerEntity> answers, Set<Long> questionIds) {
    }

    /**
     * 채점 대상을 읽고, 분석 서버가 422로 즉시 거부하는 조건을 요청 전에 걸러낸다.
     * 콜백까지 갔다 오면 사용자는 한참 기다린 끝에 실패를 보게 된다.
     */
    private Transcript loadTranscript(InterviewEntity interview) {
        Long interviewId = interview.getInterviewId();

        List<QuestionEntity> questions =
                questionRepository.findAllByInterviewIdOrderByQuestionIdAsc(interviewId);
        List<AnswerEntity> allAnswers =
                answerRepository.findAllByInterviewIdOrderByAnswerIdAsc(interviewId);

        // 면접이 끝나야 채팅 서버가 기록을 넘겨준다. 그 전에는 채점할 것이 없다.
        if (questions.isEmpty() && interview.getStatus() == Status.IN_PROGRESS) {
            throw BusinessException.unprocessable("면접이 아직 끝나지 않았습니다. 면접을 마친 뒤 다시 요청해주세요.");
        }

        Set<Long> questionIds = questions.stream()
                .map(QuestionEntity::getQuestionId)
                .collect(Collectors.toSet());

        List<AnswerEntity> answers = new ArrayList<>();
        for (AnswerEntity answer : allAnswers) {
            if (!questionIds.contains(answer.getQuestionId())) {
                // 질문 없는 답변을 실어 보내면 분석 서버가 요청 전체를 거부한다.
                log.warn("답변에 대응하는 질문이 없어 채점에서 제외합니다. interviewId={}, questionId={}",
                        interviewId, answer.getQuestionId());
                continue;
            }
            answers.add(answer);
        }

        if (questions.isEmpty()) {
            throw BusinessException.unprocessable("채점할 질문이 없습니다.");
        }
        if (questions.size() > MAX_QUESTIONS) {
            throw BusinessException.unprocessable("질문은 최대 " + MAX_QUESTIONS + "개까지 채점할 수 있습니다.");
        }
        if (answers.isEmpty()) {
            throw BusinessException.unprocessable("채점할 답변이 없습니다.");
        }

        return new Transcript(questions, answers, questionIds);
    }

    private FeedbackSoloRequest toSoloRequest(InterviewEntity interview) {
        Transcript transcript = loadTranscript(interview);

        return FeedbackSoloRequest.builder()
                .sessionId(interview.getSessionId())
                .interviewId(String.valueOf(interview.getInterviewId()))
                .userId(String.valueOf(interview.getUserId()))
                .personaType(personaTypeOf(interview))
                .callbackUrl(callbackBaseUrl + CALLBACK_PATH)
                .questions(transcript.questions().stream()
                        .map(question -> toQuestion(question, transcript.questionIds()))
                        .toList())
                .answers(transcript.answers().stream()
                        .map(answer -> FeedbackSoloRequest.Answer.builder()
                                .answerId(String.valueOf(answer.getAnswerId()))
                                .questionId(String.valueOf(answer.getQuestionId()))
                                .content(answer.getContent())
                                .createdAt(toUtc(answer.getCreatedAt()))
                                .build())
                        .toList())
                .build();
    }

    /**
     * N:1 채점 요청.
     *
     * <p>1:1과 다른 점은 두 가지다 — 참여 면접관 명단이 함께 나가고, 질문마다 누가 물었는지가 붙는다.
     * 명단이 있어야 담당 문항이 없는 면접관도 결과에 자리를 얻고, 질문별 면접관이 있어야
     * 면접관별 평가가 나뉜다.
     */
    private FeedbackMultiRequest toMultiRequest(InterviewEntity interview) {
        Transcript transcript = loadTranscript(interview);
        List<PersonaEntity> members = orderedPersonas(interview.getInterviewId());
        Map<Long, Long> personaByQuestion = resolveQuestionPersonas(transcript, members.getFirst().getPersonaId());

        return FeedbackMultiRequest.builder()
                .sessionId(interview.getSessionId())
                .interviewId(String.valueOf(interview.getInterviewId()))
                .userId(String.valueOf(interview.getUserId()))
                .personas(members.stream()
                        .map(persona -> FeedbackMultiRequest.Persona.builder()
                                .personaId(String.valueOf(persona.getPersonaId()))
                                .role(persona.getRole() == null ? null : persona.getRole().name())
                                .style(persona.getType() == null ? null : persona.getType().name())
                                .build())
                        .toList())
                .callbackUrl(callbackBaseUrl + CALLBACK_PATH)
                .questions(transcript.questions().stream()
                        .map(question -> toMultiQuestion(question, transcript.questionIds(), personaByQuestion))
                        .toList())
                .answers(transcript.answers().stream()
                        .map(answer -> FeedbackMultiRequest.Answer.builder()
                                .answerId(String.valueOf(answer.getAnswerId()))
                                .questionId(String.valueOf(answer.getQuestionId()))
                                .content(answer.getContent())
                                .createdAt(toUtc(answer.getCreatedAt()))
                                .build())
                        .toList())
                .build();
    }

    /** 면접 진행 순서대로 정리한 면접관. 맨 앞이 기술 면접관이다. */
    private List<PersonaEntity> orderedPersonas(Long interviewId) {
        List<Long> personaIds = interviewPersonaRepository
                .findAllByInterviewIdOrderByPersonaOrderAsc(interviewId).stream()
                .map(InterviewPersonaEntity::getPersonaId)
                .toList();
        if (personaIds.isEmpty()) {
            throw BusinessException.unprocessable("면접에 참여한 면접관을 찾을 수 없습니다.");
        }

        Map<Long, PersonaEntity> found = personaRepository.findAllById(personaIds).stream()
                .collect(Collectors.toMap(PersonaEntity::getPersonaId, persona -> persona));

        List<PersonaEntity> ordered = new ArrayList<>();
        for (Long personaId : personaIds) {
            PersonaEntity persona = found.get(personaId);
            if (persona == null) {
                throw BusinessException.notFound("면접관을 찾을 수 없습니다: " + personaId);
            }
            ordered.add(persona);
        }
        return ordered;
    }

    /**
     * 질문마다 누가 물었는지를 정한다. 이 값이 비면 분석 서버가 요청 전체를 422로 거부한다.
     *
     * <p>꼬리질문은 부모의 면접관을 물려받는다. 채팅 서버가 값을 달아 보내지만, 예전 면접 기록에는
     * 그 자리가 비어 있다. 부모까지 비어 있으면 기술 면접관 것으로 돌린다 — 한 문항 때문에
     * 면접 전체가 채점되지 않는 편보다 낫다.
     *
     * <p>질문은 저장 순서대로 읽히고 부모가 자식보다 먼저 저장되므로, 한 번 훑으면 부모가 먼저 채워진다.
     */
    private Map<Long, Long> resolveQuestionPersonas(Transcript transcript, Long fallbackPersonaId) {
        Map<Long, Long> personaByQuestion = new HashMap<>();

        for (QuestionEntity question : transcript.questions()) {
            Long personaId = question.getPersonaId();
            if (personaId == null && question.getParentId() != null) {
                personaId = personaByQuestion.get(question.getParentId());
            }
            if (personaId == null) {
                log.warn("질문에 면접관이 없어 기술 면접관으로 채점합니다. questionId={}", question.getQuestionId());
                personaId = fallbackPersonaId;
            }
            personaByQuestion.put(question.getQuestionId(), personaId);
        }

        return personaByQuestion;
    }

    private FeedbackSoloRequest.Question toQuestion(QuestionEntity question, Set<Long> questionIds) {
        Type type = typeOf(question, questionIds);

        // ORIGINAL에 parentId가 실려 있거나 FOLLOW에 없으면 분석 서버가 요청 전체를 422로 거부한다.
        String parentId = type == Type.FOLLOW ? String.valueOf(question.getParentId()) : null;

        return FeedbackSoloRequest.Question.builder()
                .questionId(String.valueOf(question.getQuestionId()))
                .parentId(parentId)
                .type(type)
                .intention(intentionOf(question))
                .content(question.getContent())
                .createdAt(toUtc(question.getCreatedAt()))
                .build();
    }

    private FeedbackMultiRequest.Question toMultiQuestion(QuestionEntity question, Set<Long> questionIds,
                                                         Map<Long, Long> personaByQuestion) {
        Type type = typeOf(question, questionIds);
        String parentId = type == Type.FOLLOW ? String.valueOf(question.getParentId()) : null;

        return FeedbackMultiRequest.Question.builder()
                .questionId(String.valueOf(question.getQuestionId()))
                .personaId(String.valueOf(personaByQuestion.get(question.getQuestionId())))
                .parentId(parentId)
                .type(type)
                .intention(intentionOf(question))
                .content(question.getContent())
                .createdAt(toUtc(question.getCreatedAt()))
                .build();
    }

    /**
     * 질문 종류. 부모를 가리키지 못하는 꼬리질문은 홑질문으로 낮춰 보낸다.
     *
     * <p>결과 저장은 부모를 못 찾아도 질문을 남긴다 — 거기서 막으면 면접 기록이 통째로
     * 사라지기 때문이다. 그렇게 남은 한 건을 FOLLOW인 채로 보내면 분석 서버가 요청 전체를
     * 422로 거부해, 이번에는 면접 전체가 채점되지 않는다.
     */
    private Type typeOf(QuestionEntity question, Set<Long> questionIds) {
        if (question.getType() != Type.FOLLOW) {
            return Type.ORIGINAL;
        }
        if (question.getParentId() != null && questionIds.contains(question.getParentId())) {
            return Type.FOLLOW;
        }

        log.warn("꼬리질문의 부모를 찾을 수 없어 단독 질문으로 보냅니다. questionId={}, parentId={}",
                question.getQuestionId(), question.getParentId());
        return Type.ORIGINAL;
    }

    /**
     * 질문 의도. 분석 서버는 이 값을 채점의 유일한 기준으로 삼아 빈 값을 받지 않는다.
     *
     * <p>채팅 서버가 만드는 꼬리질문은 의도가 비어 올 수 있다. 그 한 건 때문에 면접 전체가
     * 채점되지 않는 편보다는, 비었다고 알리고 나머지를 채점받는 편이 낫다.
     */
    private String intentionOf(QuestionEntity question) {
        if (question.getIntention() != null && !question.getIntention().isBlank()) {
            return question.getIntention();
        }
        log.warn("질문 의도가 비어 있어 채점 기준 없이 보냅니다. questionId={}", question.getQuestionId());
        return INTENTION_MISSING;
    }

    /**
     * 분석 서버는 콜백 전송에 두 번 실패하면 결과를 영구 폐기한다.
     * 그 경우 콜백이 영영 오지 않으므로, 오래 걸린 PENDING은 실패로 정리해 재요청을 열어준다.
     */
    private void expireIfTimedOut(FeedbackEntity feedback) {
        if (feedback.getStatus() != FeedbackStatus.PENDING || feedback.getCreatedAt() == null) {
            return;
        }
        if (feedback.getCreatedAt().plus(pendingTimeout).isAfter(LocalDateTime.now())) {
            return;
        }

        log.warn("피드백 콜백이 {} 내에 도착하지 않아 실패 처리합니다. feedbackId={}, jobId={}",
                pendingTimeout, feedback.getFeedbackId(), feedback.getJobId());

        feedback.setStatus(FeedbackStatus.FAILED);
        feedback.setErrorMessage("피드백 생성 결과를 제때 받지 못했습니다. 다시 시도해주세요.");
        feedbackRepository.save(feedback);
    }

    private Long currentUserId(String authorization) {
        UserResponse user = authServerClient.getUser(authorization);
        if (user == null || user.getId() == null) {
            throw BusinessException.unauthorized("사용자 정보를 확인할 수 없습니다. 다시 로그인해주세요.");
        }
        return user.getId();
    }

    // 면접 답변과 평가는 본인만 볼 수 있어야 한다.
    private void verifyOwner(Long ownerId, Long requesterId) {
        if (!requesterId.equals(ownerId)) {
            throw BusinessException.forbidden("본인의 면접 피드백만 조회할 수 있습니다.");
        }
    }

    /**
     * 면접관 성향. 채팅 서버는 이 값을 모른다 — 면접을 열 때 넘기지 않으므로 돌려받을 수도 없다.
     * 우리 DB가 원본이라 여기서 직접 읽는다.
     *
     * <p>N:1 면접은 면접관이 여럿이라 interview.personaId가 비어 있다. 그때는 성향 없이 보낸다.
     */
    private String personaTypeOf(InterviewEntity interview) {
        if (interview.getPersonaId() == null) {
            return null;
        }
        return personaRepository.findById(interview.getPersonaId())
                .map(PersonaEntity::getType)
                .map(Enum::name)
                .orElse(null);
    }

    /**
     * 채팅 서버는 오프셋 없는 시각을 준다. 두 서버 모두 컨테이너에 TZ를 두지 않아 JVM 기본
     * 시간대가 UTC이므로 그대로 UTC로 읽는다. 한쪽 배포에 TZ가 붙으면 여기서부터 어긋난다.
     */
    private OffsetDateTime toUtc(LocalDateTime createdAt) {
        if (createdAt == null) {
            return null;
        }
        return createdAt.atOffset(ZoneOffset.UTC);
    }

    /**
     * 분석 서버가 채점을 마치고 보내는 콜백. 재전송이 있을 수 있어 같은 결과를 두 번 받아도 안전해야 한다.
     */
    @Transactional
    public void handleCallback(FeedbackCallbackRequest request) {
        FeedbackEntity feedback = findTarget(request);
        if (feedback == null) {
            log.warn("알 수 없는 피드백 콜백을 받았습니다. jobId={}, sessionId={}",
                    request.getJobId(), request.getSessionId());
            return;
        }

        if (feedback.getJobId() == null) {
            feedback.setJobId(request.getJobId());
        }

        if (STATUS_SUCCEEDED.equalsIgnoreCase(request.getStatus()) && request.getResult() != null) {
            applySuccess(feedback, request.getResult());
        } else {
            applyFailure(feedback, request.getError());
        }

        feedbackRepository.save(feedback);
    }

    private FeedbackEntity findTarget(FeedbackCallbackRequest request) {
        if (request.getJobId() != null) {
            FeedbackEntity byJobId = feedbackRepository.findByJobId(request.getJobId()).orElse(null);
            if (byJobId != null) {
                return byJobId;
            }
        }
        if (request.getSessionId() == null) {
            return null;
        }

        FeedbackEntity bySession =
                feedbackRepository.findTopBySessionIdOrderByCreatedAtDesc(request.getSessionId()).orElse(null);
        if (bySession != null) {
            return bySession;
        }
        return createFromSession(request.getSessionId());
    }

    /**
     * 접수 기록이 없는 콜백을 세션으로 되짚어 받아낸다.
     *
     * <p>채점을 요청하는 쪽은 언제나 이 서버다. 채팅 서버는 웹소켓만 맡고, 면접이 끝나면
     * 기록을 {@code POST /api/interviews/result}로 넘길 뿐 분석 서버를 부르지 않는다.
     * 그러니 정상적인 흐름이라면 콜백에 대응하는 행이 이미 있다.
     *
     * <p>없을 수 있는 경우는 하나다 — 접수는 됐는데 202 응답을 우리가 못 받은 때. 그러면
     * 요청은 예외로 끝나 행이 남지 않지만 채점은 그대로 돌아 콜백이 온다. 여기서 받아두지
     * 않으면 분석 서버는 두 번 시도한 뒤 결과를 영구 폐기하고, 사용자는 채점이 끝났는데도
     * 아무것도 보지 못한다.
     */
    private FeedbackEntity createFromSession(String sessionId) {
        InterviewEntity interview = interviewRepository.findBySessionId(sessionId).orElse(null);
        if (interview == null) {
            return null;
        }
        return feedbackRepository.save(FeedbackEntity.builder()
                .interviewId(interview.getInterviewId())
                .userId(interview.getUserId())
                .sessionId(sessionId)
                .status(FeedbackStatus.PENDING)
                .build());
    }

    private void applySuccess(FeedbackEntity feedback, FeedbackCallbackRequest.Result result) {
        FeedbackCallbackRequest.Overall overall = result.getOverall();
        if (overall != null) {
            feedback.setTotalScore(overall.getTotalScore());
            feedback.setIntentAlignmentScore(overall.getIntentAlignmentScore());
            feedback.setReliabilityScore(overall.getReliabilityScore());
            feedback.setSummary(overall.getSummary());
            feedback.setStrengths(overall.getStrengths());
            feedback.setImprovements(overall.getImprovements());
            feedback.setFrequentWords(overall.getFrequentWords());
            feedback.setAnsweredCount(overall.getAnsweredCount());
            feedback.setQuestionCount(overall.getQuestionCount());
        }
        feedback.setStatus(FeedbackStatus.SUCCEEDED);
        feedback.setErrorStatusCode(null);
        feedback.setErrorMessage(null);

        // 콜백이 재전송되어도 문항·면접관이 중복되지 않도록 기존 것을 지우고 다시 넣는다.
        feedbackPersonaRepository.deleteAllByFeedbackId(feedback.getFeedbackId());
        feedbackItemRepository.deleteAllByFeedbackId(feedback.getFeedbackId());

        savePersonas(feedback, result.getPersonas());

        List<FeedbackCallbackRequest.Item> items = result.getFeedbacks() == null ? List.of() : result.getFeedbacks();
        List<FeedbackItemEntity> entities = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            FeedbackCallbackRequest.Item item = items.get(i);
            entities.add(FeedbackItemEntity.builder()
                    .feedbackId(feedback.getFeedbackId())
                    .questionId(Objects.toString(item.getQuestionId(), ""))
                    .sortOrder(i)
                    .personaId(item.getPersonaId())
                    .questionContent(item.getQuestionContent())
                    .intention(item.getIntention())
                    .userAnswer(item.getUserAnswer())
                    .modelAnswer(item.getModelAnswer())
                    .strengths(item.getStrengths())
                    .improvements(item.getImprovements())
                    .comment(item.getComment())
                    .build());
        }
        feedbackItemRepository.saveAll(entities);
    }

    /** 1:1 콜백에는 personas가 없다. 없으면 아무것도 저장하지 않는다. */
    private void savePersonas(FeedbackEntity feedback, List<FeedbackCallbackRequest.Persona> personas) {
        if (personas == null || personas.isEmpty()) {
            return;
        }

        List<FeedbackPersonaEntity> entities = new ArrayList<>();
        for (int i = 0; i < personas.size(); i++) {
            FeedbackCallbackRequest.Persona persona = personas.get(i);
            entities.add(FeedbackPersonaEntity.builder()
                    .feedbackId(feedback.getFeedbackId())
                    .personaId(persona.getPersonaId())
                    .personaRole(persona.getPersonaRole())
                    .sortOrder(i)
                    .score(persona.getScore())
                    .comment(persona.getComment())
                    .strengths(persona.getStrengths())
                    .improvements(persona.getImprovements())
                    .answeredCount(persona.getAnsweredCount())
                    .questionCount(persona.getQuestionCount())
                    .build());
        }
        feedbackPersonaRepository.saveAll(entities);
    }

    private void applyFailure(FeedbackEntity feedback, FeedbackCallbackRequest.Error error) {
        feedback.setStatus(FeedbackStatus.FAILED);
        feedback.setErrorStatusCode(error == null ? null : error.getStatusCode());
        feedback.setErrorMessage(error == null
                ? "피드백 생성에 실패했습니다."
                : error.getMessage());
    }

    // 인증 서버 호출이 들어가므로 마찬가지로 트랜잭션 밖에서 처리한다.
    public FeedbackResponse getFeedback(String authorization, Long interviewId) {
        Long userId = currentUserId(authorization);

        FeedbackEntity feedback = feedbackRepository.findTopByInterviewIdOrderByCreatedAtDesc(interviewId)
                .orElseThrow(() -> BusinessException.notFound("피드백이 없습니다. 먼저 피드백 생성을 요청해주세요."));
        verifyOwner(feedback.getUserId(), userId);

        // 폴링하는 클라이언트가 PENDING에 갇히지 않도록 조회 시점에도 판정한다.
        expireIfTimedOut(feedback);

        List<FeedbackPersonaEntity> personas =
                feedbackPersonaRepository.findAllByFeedbackIdOrderBySortOrderAsc(feedback.getFeedbackId());
        List<FeedbackItemEntity> items =
                feedbackItemRepository.findAllByFeedbackIdOrderBySortOrderAsc(feedback.getFeedbackId());

        return FeedbackResponse.of(feedback, personas, items);
    }
}
