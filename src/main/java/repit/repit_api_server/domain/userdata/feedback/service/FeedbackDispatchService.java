package repit.repit_api_server.domain.userdata.feedback.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatusCode;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import repit.repit_api_server.domain.userdata.answer.repository.AnswerRepository;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackDispatchEntity;
import repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackDispatchStatus;
import repit.repit_api_server.domain.userdata.feedback.repository.FeedbackDispatchRepository;
import repit.repit_api_server.domain.userdata.question.entity.QuestionEntity;
import repit.repit_api_server.domain.userdata.question.repository.QuestionRepository;
import repit.repit_api_server.domain.userdata.recording.entity.InterviewRecordingEntity;
import repit.repit_api_server.domain.userdata.recording.repository.InterviewRecordingRepository;
import repit.repit_api_server.global.exception.BusinessException;
import repit.repit_api_server.global.exception.ExternalApiException;
import software.amazon.awssdk.core.exception.SdkException;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 면접이 끝난 뒤 피드백 요청을 답변 영상이 모일 때까지 미루고, 실패하면 다시 시도한다.
 *
 * <p>질문·답변은 채팅 서버가 면접을 마칠 때, 영상은 웹이 답변을 마칠 때마다 따로 들어온다.
 * 어느 쪽이 먼저 올지 정해져 있지 않아서 두 입구 모두에서 "다 모였는지"를 본다.
 *
 * <ul>
 *   <li>다 모였다 = 기록이 저장됐고, 답한 질문마다 영상이 있다. 그러면 곧바로 요청한다.</li>
 *   <li>텍스트로 답한 질문은 영상이 영영 오지 않는다. 그래서 마지막으로 무언가 들어온 뒤
 *       유예 시간이 지나면 주기 스윕이 있는 영상만으로 요청한다. 영상이 없어도 채점은 한다.</li>
 * </ul>
 *
 * <p>요청 결과에 따라 닫는 방식이 다르다.
 * <ul>
 *   <li>접수됐거나 이미 접수돼 있었다 -> DONE.</li>
 *   <li>접수되지 않은 게 확실한 실패(연결 실패, 분석 서버의 5xx·408·429 응답, S3 서명 오류) -> 대기 시간을
 *       늘려가며 WAITING으로 되돌린다.</li>
 *   <li>접수됐는지 모르는 실패(보냈는데 응답을 못 받음, 우리 DB 오류) -> 피드백 콜백을 기다리는 시간만큼
 *       미룬 뒤 다시 본다. 그 사이 콜백이 오면 피드백 행이 생겨 재시도는 "이미 접수됨"으로 끝난다.</li>
 *   <li>다시 해도 같은 실패(분석 서버의 다른 4xx, 요청 전에 걸러낸 거절) -> 곧바로 FAILED.</li>
 * </ul>
 * 재시도하는 실패는 시도 한도를 넘으면 FAILED로 닫는다.
 * 실패를 요청한 쪽으로 올리지는 않는다. 기록 저장 응답이 실패면 채팅 서버의 완료 처리가 끊기고,
 * 업로드 응답이 실패면 웹이 같은 영상을 또 올린다.
 */
@Service
@RequiredArgsConstructor
public class FeedbackDispatchService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackDispatchService.class);

    private static final String EXPIRED_CLAIM_REASON = "요청 도중 처리가 끊겨 다시 기다립니다.";
    private static final String TOO_MANY_ATTEMPTS = "시도 한도를 넘어 자동 채점을 멈춥니다.";

    private final FeedbackDispatchRepository dispatchRepository;
    private final InterviewRecordingRepository recordingRepository;
    private final QuestionRepository questionRepository;
    private final AnswerRepository answerRepository;
    private final FeedbackService feedbackService;

    // 기록이 저장된 뒤 영상이 덜 모였을 때, 마지막으로 무언가 들어오고 이만큼 조용하면 있는 영상만 싣고 요청한다.
    @Value("${app.feedback.dispatch.upload-grace:2m}")
    private Duration uploadGrace;

    // 차지한 뒤 이만큼 끝나지 않으면 요청하던 프로세스가 사라진 것으로 본다. 한 번의 요청이 걸리는
    // 가장 긴 시간(분석 서버 읽기 제한 60초)보다 넉넉히 길어야 멀쩡히 요청 중인 건을 가로채지 않는다.
    @Value("${app.feedback.dispatch.claim-timeout:5m}")
    private Duration claimTimeout;

    @Value("${app.feedback.dispatch.max-attempts:5}")
    private int maxAttempts;

    // n번째 실패 뒤 기다리는 시간 = 기본 * 2^(n-1), 상한까지.
    @Value("${app.feedback.dispatch.retry-base-delay:30s}")
    private Duration retryBaseDelay;

    @Value("${app.feedback.dispatch.retry-max-delay:10m}")
    private Duration retryMaxDelay;

    // 접수됐는지 모르는 실패 뒤 다시 보기까지 기다리는 시간. 피드백 콜백을 기다리는 한도와 같게 둔다 —
    // 첫 요청이 실제로 접수됐다면 그 안에 콜백이 와서 피드백 행이 생기고, 재시도는 중복 없이 건너뛴다.
    @Value("${app.feedback.pending-timeout:5m}")
    private Duration unconfirmedRetryDelay;

    /** 채팅 서버가 넘긴 질문·답변이 저장된 뒤 부른다. 같은 면접의 기록이 여러 번 와도 한 번만 요청한다. */
    public void onTranscriptSaved(Long interviewId) {
        FeedbackDispatchEntity dispatch = dispatchRepository.findByInterviewId(interviewId).orElse(null);
        if (dispatch == null) {
            dispatch = createWaiting(interviewId);
        } else {
            dispatchRepository.touchIfWaiting(interviewId, now());
        }
        dispatchIfComplete(dispatch);
    }

    /** 웹이 영상을 하나 올린 뒤 부른다. 기록이 아직 오지 않았으면 기록이 올 때 함께 본다. */
    public void onRecordingUploaded(Long interviewId) {
        FeedbackDispatchEntity dispatch = dispatchRepository.findByInterviewId(interviewId).orElse(null);
        if (dispatch == null) {
            return;
        }
        if (dispatchRepository.touchIfWaiting(interviewId, now()) == 0) {
            // 이미 채점을 요청했거나 요청하는 중에 도착한 영상이다. 이번 채점에는 들어가지 않는다.
            log.warn("피드백을 이미 요청한 면접에 영상이 늦게 올라왔습니다. interviewId={}, status={}",
                    interviewId, dispatch.getStatus());
            return;
        }
        dispatchIfComplete(dispatch);
    }

    /**
     * 끊긴 요청을 되살리고, 영상이 덜 모인 채 조용해졌거나 다시 시도할 때가 된 면접을 요청한다.
     *
     * <p>한 건이 넘어져도 나머지는 계속 보낸다. 실패한 건 하나 때문에 스윕이 멈추면 그 뒤 면접이 모두 매달린다.
     */
    @Scheduled(fixedDelayString = "${app.feedback.dispatch.sweep-interval:30s}")
    public void sweep() {
        LocalDateTime now = now();

        int released = dispatchRepository.releaseExpiredClaims(now.minus(claimTimeout), EXPIRED_CLAIM_REASON);
        if (released > 0) {
            log.warn("요청 도중 끊긴 채점 요청을 다시 기다리게 했습니다. count={}", released);
        }

        for (FeedbackDispatchEntity dispatch : dispatchRepository.findDue(now.minus(uploadGrace), now)) {
            try {
                dispatch(dispatch.getDispatchId());
            } catch (RuntimeException e) {
                log.error("미뤄둔 피드백을 요청하지 못했습니다. dispatchId={}, interviewId={}",
                        dispatch.getDispatchId(), dispatch.getInterviewId(), e);
            }
        }
    }

    private FeedbackDispatchEntity createWaiting(Long interviewId) {
        try {
            return dispatchRepository.save(FeedbackDispatchEntity.builder()
                    .interviewId(interviewId)
                    .status(FeedbackDispatchStatus.WAITING)
                    .lastActivityAt(now())
                    .build());
        } catch (DataIntegrityViolationException e) {
            // 같은 면접의 기록이 동시에 두 번 들어와 다른 쪽이 먼저 만들었다.
            return dispatchRepository.findByInterviewId(interviewId).orElseThrow(() -> e);
        }
    }

    private void dispatchIfComplete(FeedbackDispatchEntity dispatch) {
        if (dispatch.getStatus() != FeedbackDispatchStatus.WAITING) {
            return;
        }
        if (hasRecordingForEveryAnswer(dispatch.getInterviewId())) {
            dispatch(dispatch.getDispatchId());
        }
    }

    /**
     * 답한 질문마다 영상이 있는지.
     *
     * <p>웹은 영상에 채팅 서버 질문 번호를 붙여 보내고, 답변은 우리 질문 PK를 가리킨다. 그래서 질문을
     * 거쳐 같은 번호 체계로 맞춘 뒤 견준다. 답변이 하나도 없으면 무엇을 기다려야 할지 모르므로 스윕에 맡긴다.
     */
    private boolean hasRecordingForEveryAnswer(Long interviewId) {
        Map<Long, Long> chatIdByQuestionId = questionRepository.findAllByInterviewId(interviewId).stream()
                .filter(question -> question.getChatQuestionId() != null)
                .collect(Collectors.toMap(QuestionEntity::getQuestionId, QuestionEntity::getChatQuestionId));

        Set<Long> answeredChatIds = answerRepository.findAllByInterviewId(interviewId).stream()
                .map(answer -> chatIdByQuestionId.get(answer.getQuestionId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (answeredChatIds.isEmpty()) {
            return false;
        }

        Set<Long> recordedChatIds = recordingRepository.findAllByInterviewIdOrderByRecordingIdAsc(interviewId).stream()
                .map(InterviewRecordingEntity::getChatQuestionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return recordedChatIds.containsAll(answeredChatIds);
    }

    private void dispatch(Long dispatchId) {
        LocalDateTime claimedAt = now();
        if (dispatchRepository.claim(dispatchId, claimedAt) == 0) {
            return;
        }
        FeedbackDispatchEntity dispatch = dispatchRepository.findById(dispatchId).orElseThrow();
        Long interviewId = dispatch.getInterviewId();
        int attempt = dispatch.getAttemptCount();

        // 차지만 하고 끊기기를 반복한 건이다. 요청을 보내기 전에 끊기면 실패로 셀 곳이 없어 여기서 멈춘다.
        if (attempt > maxAttempts) {
            close(dispatchRepository.markFailed(dispatchId, claimedAt, TOO_MANY_ATTEMPTS), interviewId);
            log.error("자동 채점 시도 한도를 넘었습니다. interviewId={}, attempts={}", interviewId, attempt);
            return;
        }

        try {
            // 이미 채점이 접수돼 있으면 FeedbackService가 건너뛴다. 그것도 요청이 끝난 것이다.
            feedbackService.requestFeedbackForFinishedInterview(interviewId);
            close(dispatchRepository.markDone(dispatchId, claimedAt), interviewId);
        } catch (RuntimeException e) {
            handleFailure(dispatchId, claimedAt, interviewId, attempt, e);
        }
    }

    private void handleFailure(Long dispatchId, LocalDateTime claimedAt, Long interviewId, int attempt,
                               RuntimeException e) {
        String reason = describe(e);
        FailureKind kind = classify(e);

        if (kind == FailureKind.PERMANENT) {
            close(dispatchRepository.markFailed(dispatchId, claimedAt, reason), interviewId);
            log.warn("다시 시도해도 같은 이유로 실패할 채점 요청이라 닫습니다. interviewId={}, 사유={}",
                    interviewId, reason);
            return;
        }
        if (attempt >= maxAttempts) {
            close(dispatchRepository.markFailed(dispatchId, claimedAt, TOO_MANY_ATTEMPTS + " 마지막 사유: " + reason),
                    interviewId);
            log.error("채점 요청이 {}번 실패해 자동 재시도를 멈춥니다. interviewId={}", attempt, interviewId, e);
            return;
        }

        if (kind == FailureKind.UNCONFIRMED) {
            LocalDateTime nextAttemptAt = now().plus(unconfirmedRetryDelay);
            close(dispatchRepository.scheduleRetry(dispatchId, claimedAt, nextAttemptAt, reason), interviewId);
            log.warn("채점 요청이 접수됐는지 알 수 없어 콜백을 기다린 뒤 다시 봅니다. interviewId={}, attempt={}, nextAttemptAt={}",
                    interviewId, attempt, nextAttemptAt, e);
            return;
        }

        LocalDateTime nextAttemptAt = now().plus(backoff(attempt));
        close(dispatchRepository.scheduleRetry(dispatchId, claimedAt, nextAttemptAt, reason), interviewId);
        log.warn("채점 요청이 실패해 다시 시도합니다. interviewId={}, attempt={}, nextAttemptAt={}",
                interviewId, attempt, nextAttemptAt, e);
    }

    enum FailureKind {
        /** 분석 서버가 받지 않은 게 확실하다. 곧 다시 보내도 채점이 겹치지 않는다. */
        NOT_ACCEPTED,
        /** 분석 서버가 받았을 수도 있다. 곧바로 다시 보내면 같은 면접이 두 번 채점될 수 있다. */
        UNCONFIRMED,
        /** 다시 보내도 같은 답이 온다. */
        PERMANENT
    }

    /**
     * 실패를 "분석 서버가 받았을 수 있는지"와 "다시 해볼 만한지"로 나눈다.
     *
     * <ul>
     *   <li>요청 전에 우리가 걸러낸 거절, 분석 서버의 4xx(408·429 제외) -> PERMANENT.</li>
     *   <li>분석 서버가 5xx·408·429로 답했다 -> 접수를 거절한 응답이다. NOT_ACCEPTED.</li>
     *   <li>연결을 맺지 못했다(연결 거부·연결 타임아웃·주소 해석 실패) -> 요청이 나가지 않았다. NOT_ACCEPTED.
     *       Reactor Netty는 이 경우 원인 체인에 ConnectException(연결 타임아웃도 그 하위 타입)이나
     *       UnknownHostException을 남긴다.</li>
     *   <li>연결은 됐는데 응답이 없다(읽기 타임아웃, 도중 끊김) -> 분석 서버가 이미 받아 채점을 시작했을 수 있다.
     *       UNCONFIRMED.</li>
     *   <li>S3 서명 오류 -> 요청 본문을 만들다 난 것이라 보내기 전이다. NOT_ACCEPTED.</li>
     *   <li>그 밖의 예외(주로 DB) -> 요청 전 읽기에서 났는지, 접수 뒤 기록에서 났는지 가를 수 없다. UNCONFIRMED.</li>
     * </ul>
     */
    static FailureKind classify(RuntimeException e) {
        if (e instanceof BusinessException) {
            return FailureKind.PERMANENT;
        }
        if (e instanceof ExternalApiException external) {
            HttpStatusCode status = external.getStatusCode();
            if (status != null) {
                boolean retryable = status.is5xxServerError() || status.value() == 408 || status.value() == 429;
                return retryable ? FailureKind.NOT_ACCEPTED : FailureKind.PERMANENT;
            }
            return neverConnected(e) ? FailureKind.NOT_ACCEPTED : FailureKind.UNCONFIRMED;
        }
        if (e instanceof SdkException) {
            return FailureKind.NOT_ACCEPTED;
        }
        return FailureKind.UNCONFIRMED;
    }

    private static boolean neverConnected(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof ConnectException
                    || cause instanceof UnknownHostException
                    || cause instanceof NoRouteToHostException) {
                return true;
            }
        }
        return false;
    }

    private Duration backoff(int attempt) {
        long factor = 1L << Math.min(attempt - 1, 20);
        Duration delay = retryBaseDelay.multipliedBy(factor);
        return delay.compareTo(retryMaxDelay) > 0 ? retryMaxDelay : delay;
    }

    private static String describe(RuntimeException e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    /** 완료 기록이 반영되지 않았다면 차지가 만료돼 다른 곳이 다시 가져간 것이다. 그쪽 결과를 덮지 않는다. */
    private static void close(int updated, Long interviewId) {
        if (updated == 0) {
            log.warn("채점 요청을 마쳤지만 그 사이 차지가 만료돼 결과를 기록하지 않았습니다. interviewId={}", interviewId);
        }
    }

    // DB 컬럼이 마이크로초까지라, 차지한 시각을 그대로 견주려면 같은 정밀도로 맞춘다.
    private static LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }
}
