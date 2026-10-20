package repit.repit_api_server.domain.userdata.analysis.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatusCode;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import repit.repit_api_server.domain.userdata.analysis.behavior.BehaviorData;
import repit.repit_api_server.domain.userdata.analysis.behavior.VideoPublicError;
import repit.repit_api_server.domain.userdata.analysis.behavior.VideoPublicErrors;
import repit.repit_api_server.domain.userdata.analysis.behavior.VideoResultDocument;
import repit.repit_api_server.domain.userdata.analysis.behavior.VideoResultException;
import repit.repit_api_server.domain.userdata.analysis.dto.request.VideoAnalysisRequest;
import repit.repit_api_server.domain.userdata.analysis.dto.response.VideoAnalysisAcceptedResponse;
import repit.repit_api_server.domain.userdata.analysis.dto.response.VideoAnalysisJobResponse;
import repit.repit_api_server.domain.userdata.analysis.dto.response.VideoAnalysisResponse;
import repit.repit_api_server.domain.userdata.analysis.entity.VideoAnalysisEntity;
import repit.repit_api_server.domain.userdata.analysis.entity.enums.VideoAnalysisRequestedBy;
import repit.repit_api_server.domain.userdata.analysis.entity.enums.VideoAnalysisStatus;
import repit.repit_api_server.domain.userdata.analysis.repository.VideoAnalysisRepository;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.interview.service.InterviewFeedbackPolicy;
import repit.repit_api_server.domain.userdata.recording.entity.InterviewRecordingEntity;
import repit.repit_api_server.domain.userdata.recording.entity.enums.RecordingKind;
import repit.repit_api_server.domain.userdata.recording.repository.InterviewRecordingRepository;
import repit.repit_api_server.global.client.AiServerClient;
import repit.repit_api_server.global.exception.BusinessException;
import repit.repit_api_server.global.exception.ExternalApiException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * 면접 화면 전체 녹화를 분석 서버의 영상 분석(POST /analysis/video)에 맡기고, 결과를 받을 때까지 지켜본다.
 *
 * <p>보내는 일은 모두 주기 스윕 한 곳에서 한다. 요청은 먼저 행(요청 id·본문)으로 남기고, 스윕이 그 행을 보내고,
 * 다시 보내고, 작업을 조회한다. 업로드나 사용자 재분석은 행을 만들기만 한다 — 응답을 오래 붙잡지 않고, 보내는
 * 규칙이 한 곳에만 있게 된다.
 *
 * <p>분석 서버와 맞춘 규칙:
 * <ul>
 *   <li>면접의 최종 영상(가장 나중에 기록된 FULL_INTERVIEW)만 보낸다. 영상 하나에 살아 있는 요청은 하나다(DB가 막는다).</li>
 *   <li>같은 요청 id로 다시 보낼 때는 저장해 둔 본문을 그대로 싣는다. 처음 포함 5회, 처음 보낸 뒤 1시간까지만.</li>
 *   <li>409는 받아들여지지 않은 것으로 닫고 새 요청을 만들지 않는다.</li>
 *   <li>콜백을 10분 기다린 뒤부터 작업을 조회한다. 처리 중이면 새 분석을 시작하지 않고 기다리고, 6시간이 지나면
 *       서비스상 실패로 닫는다(분석 서버 작업이 취소되는 것은 아니다). 그 뒤로는 사용자 재분석만 받는다.</li>
 *   <li>조회가 404면 같은 요청 id·본문으로 다시 접수한다. 다시 보낼 수 없을 때만 새 요청 id로 넘어간다.</li>
 *   <li>조회 장애·5xx·타임아웃만으로는 새 분석을 시작하지 않는다.</li>
 *   <li>자동 재분석은 분석 서버가 retryable=true로 끝낸 실패만, 묶음당 작업 5개까지. 사용자 재분석은 영상당 2회.</li>
 * </ul>
 *
 * <p>정상 완료(COMPLETED)로 저장된 음성 면접만 분석한다({@link VideoAnalysisEligibility}). 새 요청·재전송·작업 조회 복구·
 * 자동/사용자 재분석·조회·늦은 콜백이 모두 같은 판단을 거친다. 중단·텍스트 면접의 녹화는 보존하되 분석하지 않고, 진행 중인
 * 면접의 녹화는 완료될 때까지 기다린다.
 *
 * <p>기능 스위치(app.video-analysis.enabled)는 새 작업을 만드는 것만 막는다 — 새 요청, 새 요청 id의 자동 재분석, 사용자
 * 재분석. 이미 맡긴 요청은 꺼져 있어도 같은 요청 id·저장한 본문으로 다시 보내고 작업을 조회하며 콜백도 받는다. 기존 작업을
 * 취소하는 스위치가 아니다. 저장된 결과는 꺼져 있어도 보여 준다.
 *
 * <p>요청 행을 만드는 길은 모두 면접 행을 잠근 트랜잭션 안에서 자격·진행 중 요청·한도를 다시 보고 만든다. 분석 서버를 부르는
 * 느린 HTTP는 이 잠금 밖(스윕)에서 한다.
 */
@Service
@RequiredArgsConstructor
public class VideoAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(VideoAnalysisService.class);

    private static final String CALLBACK_PATH = "/api/analyses/video/callback";
    private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {
    };

    // ---- 분석 서버와 맞춘 값. 바꾸려면 분석 서버와 다시 합의한다. ----
    static final long MAX_VIDEO_BYTES = 1_000_000_000L;
    private static final Set<String> VIDEO_CONTENT_TYPES = Set.of("video/mp4", "video/webm");
    // 같은 요청 id로 보내는 횟수(처음 포함)와 기간.
    static final int MAX_SENDS = 5;
    static final Duration SEND_WINDOW = Duration.ofHours(1);
    // 자동 재분석을 포함해 한 묶음이 만드는 작업 수.
    static final int MAX_JOBS_PER_CHAIN = 5;
    static final int MAX_USER_RETRIES = 2;
    // 콜백을 이만큼 기다린 뒤부터 작업을 조회한다.
    static final Duration PENDING_TIMEOUT = Duration.ofMinutes(10);
    // 처리 중이어도 이만큼 지나면 서비스상 실패로 닫는다.
    static final Duration PROCESSING_LIMIT = Duration.ofHours(6);
    // 원본은 업로드 90일 뒤 지워진다. 분석 서버가 내려받을 하루를 남긴다.
    static final Duration ANALYZABLE_FOR = Duration.ofDays(89);

    // ---- 이 서버의 운영 값. ----
    // 마지막 업로드 뒤 이만큼 조용하면 보낸다. 연달아 다시 올리는 경우를 한 번에 받는다.
    static final Duration UPLOAD_GRACE = Duration.ofMinutes(2);
    // 서명 URL 유효 시간. 같은 요청 id로 다시 보내는 1시간 동안 만료되면 안 된다.
    static final Duration URL_TTL = Duration.ofHours(6);
    static final Duration CHECK_INTERVAL = Duration.ofMinutes(1);
    static final Duration RESEND_BASE_DELAY = Duration.ofSeconds(30);
    static final Duration REANALYSIS_DELAY = Duration.ofMinutes(1);
    // 면접이 아직 끝나지 않은 요청을 다시 볼 간격. 닫지 않고 완료를 기다린다.
    static final Duration HOLD_INTERVAL = Duration.ofMinutes(10);
    // 한 번의 스윕에서 단계마다 다루는 수. 켜는 순간 쌓인 영상이 한꺼번에 나가지 않게 한다.
    static final int BATCH_SIZE = 20;

    // ---- 웹에 내보내는 사유(reasonCode). 오류 코드는 VideoPublicErrors가 정한다. ----
    static final String REASON_TEXT_INTERVIEW = "TEXT_INTERVIEW";
    static final String REASON_NO_RECORDING = "NO_RECORDING";
    static final String REASON_UPLOADED_BEFORE_ENABLED = "UPLOADED_BEFORE_ENABLED";
    static final String REASON_SOURCE_EXPIRED = "SOURCE_EXPIRED";
    static final String REASON_SERVICE_DISABLED = "SERVICE_DISABLED";
    static final String REASON_RESULT_SCHEMA_UNSUPPORTED = "RESULT_SCHEMA_UNSUPPORTED";
    // 하위 호환 result로 내보내는 완료 문서. failed 문서는 결과가 없어 내보내지 않는다.
    private static final Set<VideoAnalysisStatus> COMPLETED_DOCUMENTS =
            Set.of(VideoAnalysisStatus.READY, VideoAnalysisStatus.PARTIAL, VideoAnalysisStatus.UNAVAILABLE);

    private final VideoAnalysisRepository analysisRepository;
    private final InterviewRepository interviewRepository;
    private final InterviewRecordingRepository recordingRepository;
    private final VideoAnalysisCallbackHandler callbackHandler;
    private final AiServerClient aiServerClient;
    private final S3Presigner s3Presigner;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    // 꺼져 있는데 요청 인증값도 없어 남은 요청을 확인하지 못한다는 경고를 한 번만 남긴다.
    private final AtomicBoolean warnedNoToken = new AtomicBoolean();

    // 분석 서버가 브라우저 샘플로 검증을 마치고 영상 분석을 열면 켠다. 꺼져 있으면 영상을 받아 두기만 한다.
    @Value("${app.video-analysis.enabled:false}")
    private boolean enabled;

    // 영상 콜백을 지키는 서버 간 인증값. 켜려면 반드시 있어야 한다.
    @Value("${app.internal-auth.token:}")
    private String internalAuthToken;

    // 분석 서버로 보내는 영상 요청·조회의 인증값(AiServerClient가 싣는다). 켜려면 반드시 있어야 한다.
    @Value("${app.video-analysis.api-token:}")
    private String videoApiToken;

    // 영상 분석을 켠 시각(오프셋을 포함한 ISO-8601). 이 시각까지 올라온 영상은 분석하지 않는다. 켜려면 반드시 있어야 한다.
    @Value("${app.video-analysis.analyze-uploaded-after:}")
    private String analyzeUploadedAfterSetting;

    // 위 값을 저장된 시각과 같은 시간대로 옮긴 것. 꺼져 있으면 null이다.
    private LocalDateTime analyzeUploadedAfter;

    @Value("${app.callback-base-url}")
    private String callbackBaseUrl;

    @Value("${spring.cloud.aws.s3.bucket}")
    private String bucketName;

    /**
     * 켰는데 서버 간 인증값이나 분석 기준 시각이 없으면 뜨지 않는다.
     *
     * <p>영상 콜백은 어긋난 본문까지 원문으로 남기므로 인증 없이는 받지 않는다(CallbackPaths.ALWAYS_AUTHENTICATED).
     * 그 상태로 요청을 보내면 콜백은 모두 401로 튕기고 결과는 조회로만 늦게 들어오는데, 겉으로는 정상처럼 보인다.
     * 요청 인증값이 없으면 접수가 모두 401로 거절돼 닫힌다. 잘못된 설정을 배포 시점에 드러낸다 — 블루/그린 배포라
     * 새 서버가 뜨지 않으면 이전 서버가 그대로 남는다.
     *
     * <p>기준 시각이 비어 있을 때 "제한 없음"으로 읽으면 켜는 순간 그동안 쌓인 영상(업로드 89일 이내)이 모두 나간다.
     * 켠 뒤 올라온 영상만 분석하기로 했으므로 값을 빠뜨린 배포를 막는다.
     */
    @PostConstruct
    void requireSettings() {
        if (!enabled) {
            return;
        }
        if (internalAuthToken == null || internalAuthToken.isBlank()) {
            throw new IllegalStateException("영상 분석(VIDEO_ANALYSIS_ENABLED)을 켜려면 INTERNAL_AUTH_TOKEN이 필요합니다. "
                    + "없으면 영상 분석 콜백을 받지 않습니다.");
        }
        if (videoApiToken == null || videoApiToken.isBlank()) {
            throw new IllegalStateException("영상 분석(VIDEO_ANALYSIS_ENABLED)을 켜려면 VIDEO_ANALYSIS_API_TOKEN이 필요합니다. "
                    + "없으면 분석 서버가 영상 요청을 모두 401로 거절합니다.");
        }
        if (analyzeUploadedAfterSetting == null || analyzeUploadedAfterSetting.isBlank()) {
            throw new IllegalStateException("영상 분석(VIDEO_ANALYSIS_ENABLED)을 켜려면 VIDEO_ANALYSIS_UPLOADED_AFTER가 필요합니다. "
                    + "없으면 켜기 전에 올라온 영상까지 분석합니다.");
        }
        try {
            analyzeUploadedAfter = OffsetDateTime.parse(analyzeUploadedAfterSetting.trim())
                    .atZoneSameInstant(ZoneId.systemDefault())
                    .toLocalDateTime();
        } catch (DateTimeParseException e) {
            throw new IllegalStateException("VIDEO_ANALYSIS_UPLOADED_AFTER는 오프셋을 포함한 ISO-8601 시각이어야 합니다. "
                    + "예: 2026-10-20T09:00:00+09:00", e);
        }
    }

    /**
     * 새로 올라온 최종 영상을 요청 자리에 올리고, 보낼 때가 된 요청을 보내거나 조회하고, 자동 재분석할 실패를 잇는다.
     *
     * <p>새 작업을 만드는 첫째·셋째 단계만 켜져 있을 때 한다. 둘째 단계(이미 맡긴 요청의 재전송·조회)는 꺼져 있어도 한다 —
     * 끄는 순간 결과를 기다리던 요청이 영영 "분석 중"으로 남지 않게 한다. 둘째 단계는 새 요청 id를 만들지 않는다.
     *
     * <p>한 건이 넘어져도 나머지는 계속한다.
     */
    @Scheduled(fixedDelayString = "${app.video-analysis.sweep-interval:30s}")
    public void sweep() {
        if (enabled) {
            LocalDateTime startedAt = now();
            LocalDateTime uploadedAfter = startedAt.minus(ANALYZABLE_FOR);
            if (analyzeUploadedAfter != null && analyzeUploadedAfter.isAfter(uploadedAfter)) {
                uploadedAfter = analyzeUploadedAfter;
            }
            for (InterviewRecordingEntity video : analysisRepository.findVideosToStart(
                    startedAt.minus(UPLOAD_GRACE), uploadedAfter, Limit.of(BATCH_SIZE))) {
                guarded("영상 분석 요청을 만들지 못했습니다.", video.getInterviewId(), () -> openFirst(video));
            }
        }
        // 방금 만든 요청도 이번에 보내도록 시각을 다시 잰다.
        List<VideoAnalysisEntity> due = analysisRepository.findDueChecks(now(), Limit.of(BATCH_SIZE));
        if (!due.isEmpty() && !enabled && (videoApiToken == null || videoApiToken.isBlank())) {
            // 꺼진 채 요청 인증값까지 빠졌다. 보내면 모두 401로 거절돼 멀쩡한 요청이 실패로 닫힌다. 확인을 미룬다.
            if (warnedNoToken.compareAndSet(false, true)) {
                log.warn("영상 분석이 꺼져 있고 VIDEO_ANALYSIS_API_TOKEN도 없어 남은 요청 {}건 이상을 확인하지 못합니다.", due.size());
            }
            due = List.of();
        }
        for (VideoAnalysisEntity analysis : due) {
            guarded("영상 분석 요청을 확인하지 못했습니다.", analysis.getInterviewId(), () -> check(analysis));
        }
        if (enabled) {
            for (VideoAnalysisEntity failed : analysisRepository.findReanalysisCandidates(
                    now().minus(REANALYSIS_DELAY), MAX_JOBS_PER_CHAIN, Limit.of(BATCH_SIZE))) {
                guarded("영상 분석을 자동으로 다시 맡기지 못했습니다.", failed.getInterviewId(), () -> reanalyze(failed));
            }
        }
    }

    /**
     * 웹이 보는 영상 분석 상태와 행동 관찰 결과. 본인 면접이 아니면 403, 없으면 404, 정상 완료되지 않은 면접이면 409다 —
     * 인증·권한·존재 오류를 "데이터 없음"으로 감추지 않는다. 분석 서버를 부르지 않는다.
     */
    public VideoAnalysisResponse get(Long userId, Long interviewId) {
        InterviewEntity interview = ownedInterview(userId, interviewId);
        InterviewFeedbackPolicy.requireEligible(interview);
        return view(interview, now());
    }

    /**
     * 최종 영상의 최신 요청으로 상태를 정하고, 같은 영상의 요청 가운데 검증을 통과한 가장 최근 ready/partial 결과를 따로
     * 골라 행동 데이터로 싣는다. 이전 영상의 결과는 섞지 않는다.
     */
    VideoAnalysisResponse view(InterviewEntity interview, LocalDateTime now) {
        if (VideoAnalysisEligibility.isText(interview)) {
            // 과거 버그로 텍스트 면접에 녹화가 있어도 행동 분석은 보여 주지 않는다.
            return VideoAnalysisResponse.empty(VideoAnalysisResponse.Status.NONE, REASON_TEXT_INTERVIEW);
        }
        InterviewRecordingEntity video = finalVideo(interview.getInterviewId());
        if (video == null) {
            return VideoAnalysisResponse.empty(VideoAnalysisResponse.Status.NONE, REASON_NO_RECORDING);
        }
        List<VideoAnalysisEntity> analyses =
                analysisRepository.findAllByRecordingIdOrderByAnalysisIdAsc(video.getRecordingId());
        if (analyses.isEmpty()) {
            if (uploadedBeforeEnabled(video)) {
                return VideoAnalysisResponse.empty(VideoAnalysisResponse.Status.NONE, REASON_UPLOADED_BEFORE_ENABLED);
            }
            if (expired(video, now)) {
                return VideoAnalysisResponse.empty(VideoAnalysisResponse.Status.NONE, REASON_SOURCE_EXPIRED);
            }
            if (!enabled) {
                // 꺼져 있으면 이 영상은 요청되지 않는다. WAITING으로 두면 웹이 끝없이 기다린다.
                return VideoAnalysisResponse.empty(VideoAnalysisResponse.Status.NONE, REASON_SERVICE_DISABLED);
            }
            return VideoAnalysisResponse.empty(VideoAnalysisResponse.Status.WAITING, null);
        }

        VideoAnalysisEntity latest = analyses.getLast();
        VideoAnalysisResponse.Status status;
        String reasonCode = null;
        VideoPublicError error = null;
        switch (latest.getStatus()) {
            case PENDING -> status = VideoAnalysisResponse.Status.ANALYZING;
            case READY -> status = VideoAnalysisResponse.Status.READY;
            case PARTIAL -> status = VideoAnalysisResponse.Status.PARTIAL;
            default -> {
                if (autoReanalysisPending(latest, analyses)) {
                    status = VideoAnalysisResponse.Status.ANALYZING;
                } else {
                    status = VideoAnalysisResponse.Status.FAILED;
                    error = VideoPublicErrors.forDisplay(latest.getErrorCode());
                    reasonCode = error.code();
                }
            }
        }

        ShownBehavior shown = latestBehavior(analyses);
        if ((status == VideoAnalysisResponse.Status.READY || status == VideoAnalysisResponse.Status.PARTIAL)
                && (shown == null || shown.analysis() != latest)) {
            // 최신 완료 결과를 이 서버가 읽을 수 없다(데이터 계약이 생기기 전의 옛 결과 등). 지어내지 않고 사유를 밝힌다.
            reasonCode = REASON_RESULT_SCHEMA_UNSUPPORTED;
        }
        // 하위 호환용 원문. 요청 순서로 가장 나중의 완료 문서다 — 늦게 도착한 옛 결과가 새 결과를 가리지 않는다.
        Map<String, Object> result = analyses.reversed().stream()
                .filter(analysis -> analysis.getResult() != null && COMPLETED_DOCUMENTS.contains(analysis.getStatus()))
                .findFirst()
                .map(VideoAnalysisEntity::getResult)
                .orElse(null);
        boolean canRetry = (status == VideoAnalysisResponse.Status.PARTIAL || status == VideoAnalysisResponse.Status.FAILED)
                && retryBlockedReason(video, analyses, now) == null;
        String latestId = String.valueOf(latest.getAnalysisId());
        String behaviorId = shown == null ? null : String.valueOf(shown.analysis().getAnalysisId());
        return new VideoAnalysisResponse(status, result, canRetry, reasonCode, error,
                shown == null ? null : shown.data(), latestId, behaviorId,
                shown != null && !behaviorId.equals(latestId));
    }

    private record ShownBehavior(VideoAnalysisEntity analysis, BehaviorData data) {
    }

    /**
     * 보여 줄 행동 데이터. 요청 순서의 역순으로 ready/partial 결과 가운데 콜백을 받을 때와 같은 검증을 통과한 첫 결과다.
     * 상태 행과 같은 행이라고 가정하지 않는다.
     */
    private static ShownBehavior latestBehavior(List<VideoAnalysisEntity> analyses) {
        for (VideoAnalysisEntity analysis : analyses.reversed()) {
            if (analysis.getResult() == null || (analysis.getStatus() != VideoAnalysisStatus.READY
                    && analysis.getStatus() != VideoAnalysisStatus.PARTIAL)) {
                continue;
            }
            try {
                VideoResultDocument.Accepted accepted =
                        VideoResultDocument.validate(analysis.getResult(), String.valueOf(analysis.getRecordingId()));
                if (accepted.behavior() != null
                        && VideoAnalysisStatus.fromResult(accepted.status()) == analysis.getStatus()) {
                    return new ShownBehavior(analysis, accepted.behavior());
                }
            } catch (VideoResultException e) {
                log.debug("저장된 영상 분석 결과를 행동 데이터로 읽을 수 없어 건너뜁니다. analysisId={}, 사유={}",
                        analysis.getAnalysisId(), e.getMessage());
            }
        }
        return null;
    }

    /**
     * 사용자가 재분석을 요청한다. 새 묶음을 열고 요청 자리만 만든다 — 보내는 것은 스윕이 곧바로 한다.
     *
     * <p>면접 행을 잠근 트랜잭션 안에서 자격·진행 중 요청·사용자 재분석 한도를 다시 보고 행을 만든다. 두 탭에서 동시에 눌러도
     * 뒤의 요청은 앞 요청이 만든 행을 보고 409로 끝난다. 영상당 진행 중 요청 하나라는 DB 인덱스는 마지막 방어선일 뿐 한도
     * 검사를 대신하지 않는다.
     */
    public VideoAnalysisResponse retry(Long userId, Long interviewId) {
        ownedInterview(userId, interviewId);
        try {
            transactionTemplate.executeWithoutResult(tx -> {
                InterviewEntity interview = interviewRepository.lockById(interviewId)
                        .orElseThrow(() -> BusinessException.notFound("면접을 찾을 수 없습니다"));
                InterviewFeedbackPolicy.requireEligible(interview);
                if (VideoAnalysisEligibility.isText(interview)) {
                    throw BusinessException.conflict("텍스트 면접은 영상 분석을 하지 않습니다.");
                }
                if (!enabled) {
                    throw BusinessException.conflict("영상 분석을 아직 사용할 수 없습니다.");
                }
                InterviewRecordingEntity video = finalVideo(interviewId);
                if (video == null) {
                    throw BusinessException.notFound("분석할 면접 영상이 없습니다.");
                }
                List<VideoAnalysisEntity> analyses =
                        analysisRepository.findAllByRecordingIdOrderByAnalysisIdAsc(video.getRecordingId());
                String blocked = retryBlockedReason(video, analyses, now());
                if (blocked != null) {
                    throw BusinessException.conflict(blocked);
                }
                insert(video, interview, newChainId(), VideoAnalysisRequestedBy.USER);
                log.info("사용자가 영상 분석을 다시 요청했습니다. interviewId={}, recordingId={}",
                        interviewId, video.getRecordingId());
            });
        } catch (DataIntegrityViolationException e) {
            // 잠금 밖에서 행을 만드는 옛 경로나 다른 서버가 먼저 자리를 차지했다.
            throw BusinessException.conflict("이미 분석 중입니다.");
        }
        return get(userId, interviewId);
    }

    /**
     * 재분석을 받을 수 없는 이유. 받을 수 있으면 null.
     *
     * <p>READY는 같은 영상이면 같은 결과가 나와 비용만 든다. 자동 재분석이 남아 있으면 그것을 기다린다. 분석 서버가
     * retryable=false로 끝낸 실패는 영상을 새로 올려야 한다. 보관 기간은 요청이 아니라 영상이 올라온 시각으로 센다 —
     * 요청 시각으로 세면 재분석할 때마다 기간이 늘어 이미 지워진 파일로 요청하게 된다.
     *
     * <p>분석 기준 시각(analyze-uploaded-after)은 요청이 한 번도 없는 영상에만 적용한다. 다시 켜면서 기준 시각을 옮겨도 이미
     * 정상적으로 분석한 영상의 재분석까지 막지는 않는다.
     */
    String retryBlockedReason(InterviewRecordingEntity video, List<VideoAnalysisEntity> analyses, LocalDateTime now) {
        if (!enabled) {
            return "영상 분석을 아직 사용할 수 없습니다.";
        }
        if (expired(video, now)) {
            return "녹화한 지 90일이 지나 다시 분석할 수 없습니다.";
        }
        if (analyses.isEmpty()) {
            return uploadedBeforeEnabled(video)
                    ? "영상 분석을 시작하기 전에 녹화한 영상이라 분석할 수 없습니다."
                    : "아직 분석을 시작하지 않았습니다.";
        }
        VideoAnalysisEntity latest = analyses.getLast();
        if (latest.getStatus() == VideoAnalysisStatus.PENDING) {
            return "이미 분석 중입니다.";
        }
        if (latest.getStatus() == VideoAnalysisStatus.READY) {
            return "이미 분석을 마쳤습니다.";
        }
        if (autoReanalysisPending(latest, analyses)) {
            return "자동으로 다시 분석하는 중입니다.";
        }
        if (Boolean.FALSE.equals(latest.getErrorRetryable())) {
            return "이 영상은 다시 분석할 수 없습니다. 영상을 새로 올려 주세요.";
        }
        long userChains = analyses.stream()
                .filter(analysis -> analysis.getRequestedBy() == VideoAnalysisRequestedBy.USER)
                .map(VideoAnalysisEntity::getChainId)
                .distinct()
                .count();
        if (userChains >= MAX_USER_RETRIES) {
            return "재분석은 영상당 " + MAX_USER_RETRIES + "번까지 할 수 있습니다.";
        }
        return null;
    }

    /**
     * 스윕이 곧 이 묶음을 자동으로 이어 갈지. {@code findReanalysisCandidates}와 같은 조건이다. 꺼져 있으면 자동 재분석을
     * 만들지 않으므로 이어 가지 않는다 — 그때 화면은 "분석 중"이 아니라 FAILED다.
     */
    private boolean autoReanalysisPending(VideoAnalysisEntity latest, List<VideoAnalysisEntity> analyses) {
        if (!enabled) {
            return false;
        }
        boolean retryableFailure = (latest.getStatus() == VideoAnalysisStatus.FAILED
                || latest.getStatus() == VideoAnalysisStatus.UNAVAILABLE)
                && Boolean.TRUE.equals(latest.getErrorRetryable());
        long jobsInChain = analyses.stream()
                .filter(analysis -> analysis.getChainId().equals(latest.getChainId()))
                .count();
        return retryableFailure && jobsInChain < MAX_JOBS_PER_CHAIN;
    }

    /** 새 최종 영상의 첫 요청. 잠금 아래에서 아직 요청이 없는지 다시 본다. */
    VideoAnalysisEntity openFirst(InterviewRecordingEntity video) {
        return openLocked(video, newChainId(), VideoAnalysisRequestedBy.AUTO, List::isEmpty);
    }

    /**
     * 면접 행을 잠그고 자격·최종 영상·요청 기록을 다시 본 뒤 요청 자리를 만든다.
     *
     * <p>스윕이 고른 뒤 잠그기 전까지 면접이 중단으로 확정되거나, 새 영상이 올라오거나, 사용자 재분석이 먼저 자리를 차지할 수
     * 있다. 고를 때의 판단을 믿지 않고 잠근 뒤 다시 본다.
     *
     * @param stillWanted 그 영상의 요청 기록(오래된 것부터)을 보고 지금도 만들어야 하는지
     * @return 만든 행. 만들 이유가 없어졌거나 같은 영상에 이미 살아 있는 요청이 있으면 null
     */
    private VideoAnalysisEntity openLocked(InterviewRecordingEntity video, String chainId,
                                           VideoAnalysisRequestedBy requestedBy,
                                           Predicate<List<VideoAnalysisEntity>> stillWanted) {
        try {
            return transactionTemplate.execute(tx -> {
                InterviewEntity interview = interviewRepository.lockById(video.getInterviewId()).orElse(null);
                if (VideoAnalysisEligibility.of(interview).kind() != VideoAnalysisEligibility.Kind.RUN) {
                    return null;
                }
                InterviewRecordingEntity finalVideo = finalVideo(video.getInterviewId());
                if (finalVideo == null || !finalVideo.getRecordingId().equals(video.getRecordingId())) {
                    return null;
                }
                List<VideoAnalysisEntity> analyses =
                        analysisRepository.findAllByRecordingIdOrderByAnalysisIdAsc(video.getRecordingId());
                if (!stillWanted.test(analyses)) {
                    return null;
                }
                return insert(video, interview, chainId, requestedBy);
            });
        } catch (DataIntegrityViolationException e) {
            log.info("같은 영상에 진행 중인 영상 분석이 있어 새로 만들지 않습니다. recordingId={}", video.getRecordingId());
            return null;
        }
    }

    /**
     * 잠금 없이 요청 자리만 만든다. 같은 영상의 살아 있는 요청 하나라는 DB 인덱스가 마지막으로 막는다. 운영 경로는
     * {@link #openLocked}를 쓴다.
     *
     * @return 만든 행. 같은 영상에 이미 살아 있는 요청이 있으면 null
     */
    VideoAnalysisEntity open(InterviewRecordingEntity video, String chainId, VideoAnalysisRequestedBy requestedBy) {
        try {
            return insert(video, interviewRepository.findById(video.getInterviewId()).orElse(null), chainId, requestedBy);
        } catch (DataIntegrityViolationException e) {
            // 다른 서버(배포 중 두 대)나 연달아 누른 재분석이 먼저 자리를 차지했다.
            log.info("같은 영상에 진행 중인 영상 분석이 있어 새로 만들지 않습니다. recordingId={}", video.getRecordingId());
            return null;
        }
    }

    /**
     * 요청 자리를 만든다. 보내는 것은 스윕이 한다. 같은 영상에 살아 있는 요청이 있으면 DB 인덱스 위반이 그대로 올라간다.
     *
     * <p>보낼 수 없는 영상(세션 없음, 입력 제한을 넘는 옛 파일 등)은 보내지 않고 닫힌 행으로 남긴다. 남기지 않으면
     * 스윕이 그 영상을 계속 다시 집는다. 서명 URL은 이 서버 안에서 계산만 하고 S3를 부르지 않는다.
     */
    private VideoAnalysisEntity insert(InterviewRecordingEntity video, InterviewEntity interview, String chainId,
                                       VideoAnalysisRequestedBy requestedBy) {
        String requestId = "video-" + UUID.randomUUID();
        Rejection rejection = rejection(video, interview);
        LocalDateTime now = now();

        VideoAnalysisEntity.VideoAnalysisEntityBuilder row = VideoAnalysisEntity.builder()
                .interviewId(video.getInterviewId())
                .userId(video.getUserId())
                .sessionId(interview == null ? null : interview.getSessionId())
                .recordingId(video.getRecordingId())
                .requestId(requestId)
                .chainId(chainId)
                .requestedBy(requestedBy);
        if (rejection != null) {
            row.status(VideoAnalysisStatus.FAILED)
                    .errorCode(rejection.code())
                    .errorRetryable(false)
                    .errorMessage(rejection.message());
        } else {
            // 서명 URL을 만드는 S3 오류는 여기서 올라가 행 없이 끝난다. 다음 스윕이 다시 집는다.
            row.status(VideoAnalysisStatus.PENDING)
                    .requestPayload(payload(requestId, interview, video))
                    .nextCheckAt(now);
        }

        VideoAnalysisEntity saved = analysisRepository.save(row.build());
        if (rejection != null) {
            log.warn("영상 분석을 보낼 수 없어 닫았습니다. interviewId={}, recordingId={}, code={}, 사유={}",
                    video.getInterviewId(), video.getRecordingId(), rejection.code(), rejection.message());
        }
        return saved;
    }

    private record Rejection(String code, String message) {
    }

    /**
     * 보내기 전에 걸러낼 수 있는 것. 코드는 분석 서버의 외부 계약 코드를 쓴다 — 같은 뜻이고, 화면도 같은 기준으로 그린다.
     *
     * <p>업로드에서 이미 막지만, 그 규칙이 생기기 전에 올라온 파일이 남아 있다.
     */
    private static Rejection rejection(InterviewRecordingEntity video, InterviewEntity interview) {
        if (interview == null) {
            return new Rejection("INTERVIEW_NOT_FOUND", "면접을 찾을 수 없습니다.");
        }
        if (video.getKind() != RecordingKind.FULL_INTERVIEW || !Objects.equals(video.getUserId(), interview.getUserId())) {
            return new Rejection("OWNER_MISMATCH", "면접 화면 녹화가 아니거나 면접의 주인이 올린 파일이 아닙니다.");
        }
        if (interview.getSessionId() == null || interview.getSessionId().isBlank()) {
            // 분석 서버는 세션 id를 요청 id와 함께 중복 판단 키로 쓴다.
            return new Rejection("SESSION_MISSING", "면접에 채팅 세션이 없습니다.");
        }
        if (video.getFileSize() == null || video.getFileSize() <= 0 || video.getFileSize() > MAX_VIDEO_BYTES) {
            return new Rejection("VIDEO_LIMIT_EXCEEDED", "영상 크기가 분석 한도를 벗어납니다. fileSize=" + video.getFileSize());
        }
        if (!VIDEO_CONTENT_TYPES.contains(video.getContentType())) {
            return new Rejection("VIDEO_FORMAT_UNSUPPORTED", "분석할 수 없는 영상 형식입니다. contentType=" + video.getContentType());
        }
        return null;
    }

    private Map<String, Object> payload(String requestId, InterviewEntity interview, InterviewRecordingEntity video) {
        VideoAnalysisRequest request = VideoAnalysisRequest.builder()
                .requestId(requestId)
                .sessionId(interview.getSessionId())
                .interviewId(String.valueOf(interview.getInterviewId()))
                .userId(String.valueOf(interview.getUserId()))
                .callbackUrl(CallbackUrls.require(callbackBaseUrl, CALLBACK_PATH))
                .video(VideoAnalysisRequest.Video.builder()
                        .videoId(String.valueOf(video.getRecordingId()))
                        .fileUrl(presign(video.getS3Key()))
                        .contentType(video.getContentType())
                        .fileSize(video.getFileSize())
                        .uploadedAt(toUtc(video.getCreatedAt()))
                        .build())
                .build();
        return objectMapper.convertValue(request, PAYLOAD_TYPE);
    }

    private String presign(String key) {
        GetObjectPresignRequest request = GetObjectPresignRequest.builder()
                .signatureDuration(URL_TTL)
                .getObjectRequest(get -> get.bucket(bucketName).key(key))
                .build();
        return s3Presigner.presignGetObject(request).url().toString();
    }

    /**
     * 결과를 기다리는 요청 하나를 한 걸음 진행한다.
     *
     * <ul>
     *   <li>작업 id가 없으면(202를 받지 못함) 같은 본문으로 보낸다.</li>
     *   <li>작업 id가 있으면 작업을 조회한다. 끝났으면 결과를 저장하고, 처리 중이면 기다린다.</li>
     * </ul>
     */
    void check(VideoAnalysisEntity analysis) {
        // 자격을 먼저 본다. 중단·텍스트 면접이면 분석 서버를 부르지 않고 닫고, 진행 중이면 닫지 않고 기다린다.
        VideoAnalysisEligibility.Verdict verdict = VideoAnalysisEligibility.of(
                interviewRepository.findById(analysis.getInterviewId()).orElse(null));
        if (verdict.kind() == VideoAnalysisEligibility.Kind.SKIP) {
            fail(analysis, verdict.code(), false, verdict.message());
            return;
        }
        if (verdict.kind() == VideoAnalysisEligibility.Kind.HOLD) {
            hold(analysis);
            return;
        }

        if (analysis.getJobId() == null) {
            if (canSendAgain(analysis, analysis.getSendCount())) {
                send(analysis);
            } else {
                giveUpSending(analysis, "같은 요청 id로 보낼 수 있는 횟수나 기간을 넘었습니다.");
            }
            return;
        }

        VideoAnalysisJobResponse job;
        try {
            job = aiServerClient.getVideoAnalysisJob(analysis.getJobId());
        } catch (ExternalApiException e) {
            int status = e.getStatusCode() == null ? 0 : e.getStatusCode().value();
            if (status == 404) {
                jobNotFound(analysis);
            } else if (status == 410) {
                fail(analysis, "RESULT_EXPIRED", null, "분석 서버의 보관 기간이 지나 결과가 지워졌습니다.");
            } else {
                // 조회가 잠깐 실패한 것과 작업이 없는 것은 다르다. 이것만으로는 아무것도 판단하지 않는다.
                waitOrGiveUp(analysis, "작업을 조회하지 못했습니다. " + describe(e));
            }
            return;
        }

        String status = job == null || job.getStatus() == null ? "" : job.getStatus().trim().toLowerCase(Locale.ROOT);
        switch (status) {
            // 종료된 작업의 result는 콜백 본문 전체다(failed 포함). 콜백과 같은 검증을 거쳐 저장한다.
            case "completed" -> applyRecovered(analysis, job.getResult());
            case "failed" -> {
                if (job.getResult() != null) {
                    applyRecovered(analysis, job.getResult());
                } else {
                    failFromJob(analysis, job.getError());
                }
            }
            default -> waitOrGiveUp(analysis, "작업이 아직 끝나지 않았습니다. status=" + status);
        }
    }

    private void applyRecovered(VideoAnalysisEntity analysis, Map<String, Object> result) {
        switch (callbackHandler.applyRecovered(analysis.getAnalysisId(), result)) {
            case INVALID -> fail(analysis, "INVALID_RESULT", null, "작업 조회로 받은 결과가 검증을 통과하지 못했습니다.");
            case POLICY_HELD -> hold(analysis);
            default -> {
                // 저장했거나, 이미 같은 결과가 있거나, 정책상 닫았다.
            }
        }
    }

    /** 면접이 아직 끝나지 않았다. 닫지 않고 나중에 다시 본다. */
    private void hold(VideoAnalysisEntity analysis) {
        LocalDateTime now = now();
        analysisRepository.postpone(analysis.getAnalysisId(), now.plus(HOLD_INTERVAL),
                "면접이 아직 끝나지 않아 결과 확인을 미룹니다.", now);
    }

    /**
     * 저장해 둔 본문을 그대로 보낸다.
     *
     * <ul>
     *   <li>202 — 작업 id를 적고 콜백을 기다린다.</li>
     *   <li>409 — 같은 요청 id에 다른 내용이라는 뜻이다. 저장한 본문만 보내므로 정상 흐름에서는 나오지 않는다.
     *       닫고 새 요청을 만들지 않는다.</li>
     *   <li>그 밖의 4xx — 받아들여지지 않았다. 닫는다.</li>
     *   <li>5xx·408·429·연결 실패·타임아웃 — 접수됐는지 모른다. 같은 본문으로 다시 보낸다.</li>
     * </ul>
     */
    private void send(VideoAnalysisEntity analysis) {
        Long analysisId = analysis.getAnalysisId();
        int sent = analysis.getSendCount() + 1;
        analysisRepository.countSend(analysisId, now());

        VideoAnalysisAcceptedResponse accepted;
        try {
            accepted = aiServerClient.requestVideoAnalysis(analysis.getRequestPayload());
        } catch (ExternalApiException e) {
            HttpStatusCode status = e.getStatusCode();
            if (status != null && status.value() == 409) {
                fail(analysis, "CONFLICT", null, "같은 요청 id에 다른 내용을 보냈다며 거절당했습니다. 새 요청을 만들지 않습니다.");
                log.error("영상 분석 요청이 409로 거절됐습니다. 원인을 확인해야 합니다. analysisId={}, requestId={}",
                        analysisId, analysis.getRequestId(), e);
                return;
            }
            if (status != null && status.is4xxClientError() && status.value() != 408 && status.value() != 429) {
                fail(analysis, "REQUEST_REJECTED", null, describe(e));
                log.error("영상 분석 요청이 거절됐습니다. analysisId={}, requestId={}, status={}",
                        analysisId, analysis.getRequestId(), status.value(), e);
                return;
            }
            sendLater(analysis, sent, describe(e));
            return;
        }

        String jobId = accepted == null ? null : accepted.getJobId();
        if (jobId == null || jobId.isBlank()) {
            sendLater(analysis, sent, "접수 응답에 작업 id가 없습니다.");
            return;
        }
        LocalDateTime now = now();
        analysisRepository.acknowledge(analysisId, jobId, now.plus(PENDING_TIMEOUT), now);
        log.info("영상 분석을 맡겼습니다. interviewId={}, analysisId={}, requestId={}, jobId={}, 보낸 횟수={}",
                analysis.getInterviewId(), analysisId, analysis.getRequestId(), jobId, sent);
    }

    private void sendLater(VideoAnalysisEntity analysis, int sent, String reason) {
        if (!canSendAgain(analysis, sent)) {
            giveUpSending(analysis, reason);
            return;
        }
        LocalDateTime now = now();
        analysisRepository.postpone(analysis.getAnalysisId(), now.plus(resendDelay(sent)), reason, now);
        log.warn("영상 분석 접수를 확인하지 못해 같은 본문으로 다시 보냅니다. analysisId={}, 보낸 횟수={}, 사유={}",
                analysis.getAnalysisId(), sent, reason);
    }

    /** 같은 요청 id로 한 번 더 보낼 수 있는지. 처음 포함 5회, 처음 보낸 뒤 1시간까지. */
    private static boolean canSendAgain(VideoAnalysisEntity analysis, int sent) {
        return sent < MAX_SENDS && now().isBefore(analysis.getCreatedAt().plus(SEND_WINDOW));
    }

    /**
     * 같은 요청 id로 더 보낼 수 없다.
     *
     * <p>202를 끝내 받지 못했으면 작업이 생겼는지 모르므로 자동으로 새 분석을 하지 않는다. 작업이 없다고 확인된
     * 뒤(404) 다시 접수하지 못한 것이면, 새로 분석해도 겹치지 않으므로 자동 재분석으로 넘긴다.
     */
    private void giveUpSending(VideoAnalysisEntity analysis, String reason) {
        if (analysis.getJobId() == null) {
            fail(analysis, "NOT_ACKNOWLEDGED", null, "접수를 확인하지 못했습니다. 마지막 사유: " + reason);
        } else {
            fail(analysis, "JOB_NOT_FOUND", true, "분석 서버에 작업이 없어 다시 접수했지만 받아들여지지 않았습니다. 마지막 사유: " + reason);
        }
    }

    /** 분석 서버에 작업이 없다. 먼저 같은 요청 id와 본문으로 다시 접수해 작업을 되살린다. */
    private void jobNotFound(VideoAnalysisEntity analysis) {
        if (canSendAgain(analysis, analysis.getSendCount())) {
            log.warn("분석 서버에 영상 분석 작업이 없어 같은 요청 id로 다시 접수합니다. analysisId={}, jobId={}",
                    analysis.getAnalysisId(), analysis.getJobId());
            send(analysis);
            return;
        }
        fail(analysis, "JOB_NOT_FOUND", true, "분석 서버에 작업이 없고 같은 요청 id로 다시 보낼 수 있는 기간이 지났습니다.");
    }

    /** 처리 중이거나 조회가 안 되면 기다린다. 처리 상한을 넘으면 서비스상 실패로 닫는다 — 분석 서버 작업은 계속될 수 있다. */
    private void waitOrGiveUp(VideoAnalysisEntity analysis, String reason) {
        LocalDateTime now = now();
        if (!now.isBefore(analysis.getCreatedAt().plus(PROCESSING_LIMIT))) {
            fail(analysis, "PROCESSING_OVERDUE", null,
                    PROCESSING_LIMIT.toHours() + "시간 안에 결과를 받지 못했습니다. 분석 서버의 작업은 취소되지 않았을 수 있습니다. 마지막 사유: " + reason);
            return;
        }
        analysisRepository.postpone(analysis.getAnalysisId(), now.plus(CHECK_INTERVAL), reason, now);
    }

    /** 결과 문서 없이 실패만 알려 온 작업. 오류도 공개 오류 계약(코드·retryable)에 맞아야 그대로 받는다. */
    private void failFromJob(VideoAnalysisEntity analysis, Map<String, Object> error) {
        String code;
        try {
            code = VideoPublicErrors.validateRemote(error, "$.error");
        } catch (VideoResultException e) {
            fail(analysis, "INVALID_RESULT", null, "작업 조회의 실패 오류가 공개 오류 계약에 맞지 않습니다. " + e.getMessage());
            return;
        }
        fail(analysis, code, VideoPublicErrors.retryableOf(code), String.valueOf(error.get("message")));
    }

    private void fail(VideoAnalysisEntity analysis, String code, Boolean retryable, String message) {
        int updated = analysisRepository.fail(analysis.getAnalysisId(), code, retryable, message, now());
        if (updated == 0) {
            log.info("영상 분석을 닫으려 했지만 그 사이 결과가 들어왔습니다. analysisId={}, code={}",
                    analysis.getAnalysisId(), code);
            return;
        }
        log.warn("영상 분석을 결과 없이 닫았습니다. interviewId={}, analysisId={}, code={}, retryable={}, 사유={}",
                analysis.getInterviewId(), analysis.getAnalysisId(), code, retryable, message);
    }

    /** 재시도할 만한 실패로 끝난 요청을 같은 묶음의 새 요청으로 잇는다. 새 서명 URL과 새 요청 id를 쓴다. */
    private void reanalyze(VideoAnalysisEntity failed) {
        InterviewRecordingEntity video = recordingRepository.findById(failed.getRecordingId()).orElse(null);
        if (video == null) {
            return;
        }
        // 잠근 뒤에도 그 실패가 영상의 최신 요청이고 묶음에 자리가 남았는지 다시 본다. 그 사이 사용자 재분석이 새 묶음을
        // 열었으면 잇지 않는다.
        VideoAnalysisEntity opened = openLocked(video, failed.getChainId(), failed.getRequestedBy(), analyses ->
                !analyses.isEmpty() && analyses.getLast().getAnalysisId().equals(failed.getAnalysisId())
                        && autoReanalysisPending(analyses.getLast(), analyses));
        if (opened != null) {
            log.info("재시도할 만한 실패라 영상 분석을 다시 맡깁니다. interviewId={}, 이전 analysisId={}, code={}",
                    failed.getInterviewId(), failed.getAnalysisId(), failed.getErrorCode());
        }
    }

    private InterviewEntity ownedInterview(Long userId, Long interviewId) {
        InterviewEntity interview = interviewRepository.findById(interviewId)
                .orElseThrow(() -> BusinessException.notFound("면접을 찾을 수 없습니다"));
        if (!Objects.equals(userId, interview.getUserId())) {
            throw BusinessException.forbidden("본인의 면접만 볼 수 있습니다.");
        }
        return interview;
    }

    /** 면접의 최종 영상. 기록된 순서(recording_id)로 가장 나중 것이다. */
    private InterviewRecordingEntity finalVideo(Long interviewId) {
        return recordingRepository
                .findFirstByInterviewIdAndKindOrderByRecordingIdDesc(interviewId, RecordingKind.FULL_INTERVIEW)
                .orElse(null);
    }

    private static boolean expired(InterviewRecordingEntity video, LocalDateTime now) {
        return !now.isBefore(video.getCreatedAt().plus(ANALYZABLE_FOR));
    }

    /** 영상 분석을 켜기 전에 올라와 분석하지 않는 영상인지. 스윕이 고르는 기준(올라온 시각 &gt; 기준 시각)과 같다. */
    private boolean uploadedBeforeEnabled(InterviewRecordingEntity video) {
        return analyzeUploadedAfter != null && !video.getCreatedAt().isAfter(analyzeUploadedAfter);
    }

    private static Duration resendDelay(int sent) {
        return RESEND_BASE_DELAY.multipliedBy(1L << Math.min(sent - 1, 10));
    }

    private static String newChainId() {
        return "chain-" + UUID.randomUUID();
    }

    private static void guarded(String failure, Long interviewId, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException e) {
            log.error("{} interviewId={}", failure, interviewId, e);
        }
    }

    private static String describe(RuntimeException e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    /** 두 서버 모두 JVM 기본 시간대가 UTC라 저장된 시각을 그대로 UTC로 읽는다. */
    private static OffsetDateTime toUtc(LocalDateTime time) {
        return time == null ? null : time.atOffset(ZoneOffset.UTC);
    }

    // DB 컬럼이 마이크로초까지라 같은 정밀도로 맞춘다.
    private static LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }
}
