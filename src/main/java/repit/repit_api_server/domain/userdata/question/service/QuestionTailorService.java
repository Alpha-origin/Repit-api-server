package repit.repit_api_server.domain.userdata.question.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import repit.repit_api_server.domain.metadata.dto.response.GenerateResultResponse;
import repit.repit_api_server.domain.metadata.dto.response.GeneratedQuestionResponse;
import repit.repit_api_server.domain.metadata.entity.AnalysisDataEntity;
import repit.repit_api_server.domain.metadata.repository.AnalysisDataRepository;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.interview.service.ChatInterviewHandoffService;
import repit.repit_api_server.domain.userdata.persona.entity.PersonaEntity;
import repit.repit_api_server.domain.userdata.persona.repository.PersonaRepository;
import repit.repit_api_server.domain.userdata.question.dto.request.QuestionTailorCallbackRequest;
import repit.repit_api_server.domain.userdata.question.dto.request.QuestionTailorRequest;
import repit.repit_api_server.domain.userdata.question.dto.response.QuestionTailorAcceptedResponse;
import repit.repit_api_server.domain.userdata.question.dto.response.QuestionTailorResponse;
import repit.repit_api_server.domain.userdata.question.dto.response.TailoredQuestionResponse;
import repit.repit_api_server.domain.userdata.question.entity.QuestionTailorEntity;
import repit.repit_api_server.domain.userdata.question.entity.enums.TailorStatus;
import repit.repit_api_server.domain.userdata.question.repository.QuestionTailorRepository;
import repit.repit_api_server.global.client.AiServerClient;
import repit.repit_api_server.global.client.AuthServerClient;
import repit.repit_api_server.global.exception.BusinessException;
import repit.repit_api_server.global.response.UserResponse;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 면접 시작 시 도는 질문 재작성 흐름.
 *
 * <p>DB에 저장된 원질문을 페르소나와 함께 분석 서버로 보내고, 콜백으로 돌아온 재작성 질문을
 * 원질문과 함께 저장한 뒤, 그 전체를 채팅 서버로 넘긴다. 재작성이 실패해도 원질문은 유효한
 * 산출물이라 어떤 경로로 끝나든 면접에 쓸 질문이 남고 채팅 서버로도 넘어간다.
 */
@Service
@RequiredArgsConstructor
public class QuestionTailorService {

    private static final Logger log = LoggerFactory.getLogger(QuestionTailorService.class);

    private static final String CALLBACK_PATH = "/api/questions/tailor/callback";
    private static final int MAX_QUESTIONS = 10;
    private static final String STATUS_SUCCEEDED = "succeeded";

    private final QuestionTailorRepository questionTailorRepository;
    private final InterviewRepository interviewRepository;
    private final PersonaRepository personaRepository;
    private final AnalysisDataRepository analysisDataRepository;
    private final AiServerClient aiServerClient;
    private final AuthServerClient authServerClient;
    private final ChatInterviewHandoffService chatInterviewHandoffService;
    private final ObjectMapper objectMapper;

    @Value("${app.callback-base-url}")
    private String callbackBaseUrl;

    // 이 시간을 넘도록 콜백이 오지 않으면 실패로 간주하고 원질문으로 진행시킨다.
    @Value("${app.question-tailor.pending-timeout:2m}")
    private Duration pendingTimeout;

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
        QuestionTailorEntity existing = questionTailorRepository
                .findTopByInterviewIdOrderByCreatedAtDesc(interview.getInterviewId())
                .orElse(null);
        if (existing != null) {
            expireIfTimedOut(existing);
            // 이미 확정된 건은 채팅 서버 전달만 마저 시도하고 끝낸다.
            deliverToChatServer(existing);
            return existing;
        }

        SourceQuestions source = loadOriginalQuestions(interview.getUserId());
        List<TailoredQuestionResponse> sourceQuestions = source.questions();
        QuestionTailorRequest.Profile profile = resolveProfile(user, interview.getPersonaId());

        QuestionTailorAcceptedResponse accepted = aiServerClient.tailorQuestions(QuestionTailorRequest.builder()
                .interviewId(String.valueOf(interview.getInterviewId()))
                .userId(String.valueOf(interview.getUserId()))
                .profile(profile)
                .questions(sourceQuestions.stream().map(this::toRequestQuestion).toList())
                .callbackUrl(callbackBaseUrl + CALLBACK_PATH)
                .build());

        return questionTailorRepository.save(QuestionTailorEntity.builder()
                .interviewId(interview.getInterviewId())
                .userId(interview.getUserId())
                .jobId(accepted == null ? null : accepted.getJobId())
                // 재작성본이 어느 분석 결과에서 나왔는지 되짚을 수 있게 남긴다.
                .analysisJobId(source.analysisJobId())
                .status(TailorStatus.PENDING)
                .sourceQuestions(sourceQuestions)
                .chatDelivered(false)
                .build());
    }

    private QuestionTailorRequest.Question toRequestQuestion(TailoredQuestionResponse question) {
        return QuestionTailorRequest.Question.builder()
                .id(question.getId())
                .category(question.getCategory())
                .question(question.getQuestion())
                .expectedAnswer(question.getExpectedAnswer())
                .basedOn(question.getBasedOn())
                .build();
    }

    /** 원질문과 그 질문이 나온 분석 작업. 재작성 건에 출처를 남겨두려고 jobId를 함께 들고 다닌다. */
    private record SourceQuestions(String analysisJobId, List<TailoredQuestionResponse> questions) {
    }

    /** 재작성 대상은 해당 사용자의 가장 최근 분석 결과에 담긴 원질문이다. */
    private SourceQuestions loadOriginalQuestions(Long userId) {
        AnalysisDataEntity analysisData = analysisDataRepository
                .findTopByUserIdAndResultIsNotNullOrderByCreatedAtDesc(userId)
                .orElseThrow(() -> BusinessException.notFound(
                        "완료된 분석 결과가 없습니다. 포트폴리오 분석을 먼저 진행해주세요."));

        GenerateResultResponse parsed = objectMapper.convertValue(analysisData.getResult(), GenerateResultResponse.class);
        List<GeneratedQuestionResponse> interview = parsed.getInterview() == null
                ? List.of()
                : parsed.getInterview();

        // 아래 조건은 분석 서버가 422로 즉시 거부하는 항목이라 요청 전에 걸러낸다.
        if (interview.isEmpty()) {
            throw BusinessException.unprocessable("다시 쓸 질문이 없습니다.");
        }
        if (interview.size() > MAX_QUESTIONS) {
            throw BusinessException.unprocessable("질문은 최대 " + MAX_QUESTIONS + "개까지 다시 쓸 수 있습니다.");
        }

        Set<Integer> seen = new HashSet<>();
        List<TailoredQuestionResponse> questions = new ArrayList<>();
        for (GeneratedQuestionResponse question : interview) {
            if (question.getId() == null || !seen.add(question.getId())) {
                throw BusinessException.unprocessable("질문 id가 중복되어 다시 쓸 수 없습니다.");
            }
            questions.add(TailoredQuestionResponse.builder()
                    .id(question.getId())
                    .category(question.getCategory())
                    .question(question.getQuestion())
                    .expectedAnswer(question.getExpected_answer())
                    .basedOn(question.getBased_on())
                    .build());
        }
        return new SourceQuestions(analysisData.getJobId(), questions);
    }

    /**
     * 세 축이 모두 비면 분석 서버가 실패 콜백(422)을 보낸다.
     * 콜백까지 갔다 오면 면접 시작이 그만큼 늦어지므로 요청 전에 막는다.
     */
    private QuestionTailorRequest.Profile resolveProfile(UserResponse user, Long personaId) {
        PersonaEntity persona = personaRepository.findById(personaId).orElse(null);

        String jobRole = blankToNull(user.getMajor());
        if (jobRole == null && persona != null && persona.getMajor() != null) {
            jobRole = persona.getMajor().name();
        }
        String personaType = persona == null || persona.getType() == null ? null : persona.getType().name();

        if (jobRole == null && personaType == null) {
            throw BusinessException.unprocessable("질문을 다시 쓸 사전 정보가 없습니다.");
        }

        return QuestionTailorRequest.Profile.builder()
                .jobRole(jobRole)
                // persona.career는 면접관 설정이지 지원자 경력이 아니다. 지원자 경력은 아직 수집하지 않는다.
                .experienceLevel(null)
                .personaType(personaType)
                .build();
    }

    /**
     * 분석 서버는 콜백 전송에 실패하면 결과를 폐기한다.
     * 그 경우 콜백이 영영 오지 않으므로, 오래 걸린 PENDING은 원질문 폴백으로 정리한다.
     */
    private void expireIfTimedOut(QuestionTailorEntity tailor) {
        if (tailor.getStatus() != TailorStatus.PENDING || tailor.getCreatedAt() == null) {
            return;
        }
        if (tailor.getCreatedAt().plus(pendingTimeout).isAfter(LocalDateTime.now())) {
            return;
        }

        log.warn("질문 재작성 콜백이 {} 내에 도착하지 않아 원질문으로 진행합니다. tailorId={}, jobId={}",
                pendingTimeout, tailor.getTailorId(), tailor.getJobId());

        fallbackToOriginal(tailor, "질문 재작성 결과를 제때 받지 못해 원질문으로 진행합니다.");
        questionTailorRepository.save(tailor);
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
        deliverToChatServer(tailor);
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
        if (tailor.getStatus() == TailorStatus.PENDING || Boolean.TRUE.equals(tailor.getChatDelivered())) {
            return;
        }

        // 차지하지 못했다면 다른 쪽이 이미 넘겼거나 넘기는 중이다. 그 결과는 다음 조회에서 읽힌다.
        if (questionTailorRepository.claimChatDelivery(tailor.getTailorId()) == 0) {
            return;
        }

        try {
            chatInterviewHandoffService.deliver(tailor);
            tailor.setChatDelivered(true);
            tailor.setChatErrorMessage(null);
        } catch (RuntimeException e) {
            log.error("면접 데이터를 채팅 서버로 넘기지 못했습니다. tailorId={}, interviewId={}",
                    tailor.getTailorId(), tailor.getInterviewId(), e);
            // 차지했던 것을 놓아준다. 그대로 두면 넘어간 적이 없는데도 넘긴 것으로 남아 다시 시도하지 못한다.
            tailor.setChatDelivered(false);
            tailor.setChatErrorMessage(e.getMessage());
        }
        questionTailorRepository.save(tailor);
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
        // 본문만 갈아끼운다. category/expectedAnswer/basedOn은 콜백에 실려오지 않아 원질문 값을 유지한다.
        tailor.setQuestions(source.stream()
                .map(question -> TailoredQuestionResponse.builder()
                        .id(question.getId())
                        .category(question.getCategory())
                        .question(rewritten.get(question.getId()))
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
    public QuestionTailorResponse getTailorResult(String authorization, Long interviewId) {
        UserResponse user = currentUser(authorization);

        InterviewEntity interview = interviewRepository.findById(interviewId)
                .orElseThrow(() -> BusinessException.notFound("면접을 찾을 수 없습니다"));
        verifyOwner(interview.getUserId(), user.getId());

        QuestionTailorEntity tailor = questionTailorRepository
                .findTopByInterviewIdOrderByCreatedAtDesc(interviewId)
                .orElse(null);
        if (tailor == null) {
            return QuestionTailorResponse.notRequested(interviewId,
                    loadOriginalQuestions(interview.getUserId()).questions());
        }

        // 폴링하는 클라이언트가 PENDING에 갇히지 않도록 조회 시점에도 판정하고, 밀린 전달을 마저 시도한다.
        expireIfTimedOut(tailor);
        deliverToChatServer(tailor);

        return QuestionTailorResponse.of(tailor);
    }

    private UserResponse currentUser(String authorization) {
        UserResponse user = authServerClient.getUser(authorization);
        if (user == null || user.getId() == null) {
            throw BusinessException.unauthorized("사용자 정보를 확인할 수 없습니다. 다시 로그인해주세요.");
        }
        return user;
    }

    // 면접 질문은 본인만 볼 수 있어야 한다.
    private void verifyOwner(Long ownerId, Long requesterId) {
        if (!requesterId.equals(ownerId)) {
            throw BusinessException.forbidden("본인의 면접 질문만 다룰 수 있습니다.");
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
