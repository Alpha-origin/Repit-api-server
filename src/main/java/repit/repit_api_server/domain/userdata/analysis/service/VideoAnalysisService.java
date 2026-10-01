package repit.repit_api_server.domain.userdata.analysis.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatusCode;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
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
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

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
    // 한 번의 스윕에서 단계마다 다루는 수. 켜는 순간 쌓인 영상이 한꺼번에 나가지 않게 한다.
    static final int BATCH_SIZE = 20;

    private final VideoAnalysisRepository analysisRepository;
    private final InterviewRepository interviewRepository;
    private final InterviewRecordingRepository recordingRepository;
    private final VideoAnalysisCallbackHandler callbackHandler;
    private final AiServerClient aiServerClient;
    private final S3Presigner s3Presigner;
    private final ObjectMapper objectMapper;

    // 분석 서버가 브라우저 샘플로 검증을 마치고 영상 분석을 열면 켠다. 꺼져 있으면 영상을 받아 두기만 한다.
    @Value("${app.video-analysis.enabled:false}")
    private boolean enabled;

    @Value("${app.callback-base-url}")
    private String callbackBaseUrl;

    @Value("${spring.cloud.aws.s3.bucket}")
    private String bucketName;

    /**
     * 새로 올라온 최종 영상을 요청 자리에 올리고, 보낼 때가 된 요청을 보내거나 조회하고, 자동 재분석할 실패를 잇는다.
     *
     * <p>한 건이 넘어져도 나머지는 계속한다.
     */
    @Scheduled(fixedDelayString = "${app.video-analysis.sweep-interval:30s}")
    public void sweep() {
        if (!enabled) {
            return;
        }
        LocalDateTime startedAt = now();
        for (InterviewRecordingEntity video : analysisRepository.findVideosToStart(
                startedAt.minus(UPLOAD_GRACE), startedAt.minus(ANALYZABLE_FOR), Limit.of(BATCH_SIZE))) {
            guarded("영상 분석 요청을 만들지 못했습니다.", video.getInterviewId(),
                    () -> open(video, newChainId(), VideoAnalysisRequestedBy.AUTO));
        }
        // 방금 만든 요청도 이번에 보내도록 시각을 다시 잰다.
        for (VideoAnalysisEntity analysis : analysisRepository.findDueChecks(now(), Limit.of(BATCH_SIZE))) {
            guarded("영상 분석 요청을 확인하지 못했습니다.", analysis.getInterviewId(), () -> check(analysis));
        }
        for (VideoAnalysisEntity failed : analysisRepository.findReanalysisCandidates(
                now().minus(REANALYSIS_DELAY), MAX_JOBS_PER_CHAIN, Limit.of(BATCH_SIZE))) {
            guarded("영상 분석을 자동으로 다시 맡기지 못했습니다.", failed.getInterviewId(), () -> reanalyze(failed));
        }
    }

    /** 웹이 보는 영상 분석 상태. */
    public VideoAnalysisResponse get(Long userId, Long interviewId) {
        ownedInterview(userId, interviewId);
        InterviewRecordingEntity video = finalVideo(interviewId);
        if (video == null) {
            return new VideoAnalysisResponse(VideoAnalysisResponse.Status.NONE, null, false);
        }
        List<VideoAnalysisEntity> analyses =
                analysisRepository.findAllByRecordingIdOrderByAnalysisIdAsc(video.getRecordingId());
        if (analyses.isEmpty()) {
            VideoAnalysisResponse.Status status = expired(video, now())
                    ? VideoAnalysisResponse.Status.NONE
                    : VideoAnalysisResponse.Status.WAITING;
            return new VideoAnalysisResponse(status, null, false);
        }

        VideoAnalysisEntity latest = analyses.getLast();
        VideoAnalysisResponse.Status status = switch (latest.getStatus()) {
            case PENDING -> VideoAnalysisResponse.Status.ANALYZING;
            case READY -> VideoAnalysisResponse.Status.READY;
            case PARTIAL -> VideoAnalysisResponse.Status.PARTIAL;
            case UNAVAILABLE, FAILED -> autoReanalysisPending(latest, analyses)
                    ? VideoAnalysisResponse.Status.ANALYZING
                    : VideoAnalysisResponse.Status.FAILED;
        };
        // 늦게 도착한 옛 결과가 새 결과를 가리지 않게, 요청 순서로 가장 나중의 완료 결과를 보여 준다.
        Map<String, Object> result = analyses.reversed().stream()
                .filter(analysis -> analysis.getResult() != null)
                .findFirst()
                .map(VideoAnalysisEntity::getResult)
                .orElse(null);
        boolean canRetry = enabled && retryBlockedReason(video, analyses, now()) == null;
        return new VideoAnalysisResponse(status, result, canRetry);
    }

    /**
     * 사용자가 재분석을 요청한다. 새 묶음을 열고 요청 자리만 만든다 — 보내는 것은 스윕이 곧바로 한다.
     */
    public VideoAnalysisResponse retry(Long userId, Long interviewId) {
        ownedInterview(userId, interviewId);
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
        if (open(video, newChainId(), VideoAnalysisRequestedBy.USER) == null) {
            throw BusinessException.conflict("이미 분석 중입니다.");
        }
        log.info("사용자가 영상 분석을 다시 요청했습니다. interviewId={}, recordingId={}", interviewId, video.getRecordingId());
        return get(userId, interviewId);
    }

    /**
     * 재분석을 받을 수 없는 이유. 받을 수 있으면 null.
     *
     * <p>READY는 같은 영상이면 같은 결과가 나와 비용만 든다. 자동 재분석이 남아 있으면 그것을 기다린다. 분석 서버가
     * retryable=false로 끝낸 실패는 영상을 새로 올려야 한다. 보관 기간은 요청이 아니라 영상이 올라온 시각으로 센다 —
     * 요청 시각으로 세면 재분석할 때마다 기간이 늘어 이미 지워진 파일로 요청하게 된다.
     */
    String retryBlockedReason(InterviewRecordingEntity video, List<VideoAnalysisEntity> analyses, LocalDateTime now) {
        if (expired(video, now)) {
            return "녹화한 지 90일이 지나 다시 분석할 수 없습니다.";
        }
        if (analyses.isEmpty()) {
            return "아직 분석을 시작하지 않았습니다.";
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

    /** 스윕이 곧 이 묶음을 자동으로 이어 갈지. {@code findReanalysisCandidates}와 같은 조건이다. */
    private static boolean autoReanalysisPending(VideoAnalysisEntity latest, List<VideoAnalysisEntity> analyses) {
        boolean retryableFailure = (latest.getStatus() == VideoAnalysisStatus.FAILED
                || latest.getStatus() == VideoAnalysisStatus.UNAVAILABLE)
                && Boolean.TRUE.equals(latest.getErrorRetryable());
        long jobsInChain = analyses.stream()
                .filter(analysis -> analysis.getChainId().equals(latest.getChainId()))
                .count();
        return retryableFailure && jobsInChain < MAX_JOBS_PER_CHAIN;
    }

    /**
     * 요청 자리를 만든다. 보내는 것은 스윕이 한다.
     *
     * <p>보낼 수 없는 영상(세션 없음, 입력 제한을 넘는 옛 파일 등)은 보내지 않고 닫힌 행으로 남긴다. 남기지 않으면
     * 스윕이 그 영상을 계속 다시 집는다.
     *
     * @return 만든 행. 같은 영상에 이미 살아 있는 요청이 있으면 null
     */
    VideoAnalysisEntity open(InterviewRecordingEntity video, String chainId, VideoAnalysisRequestedBy requestedBy) {
        InterviewEntity interview = interviewRepository.findById(video.getInterviewId()).orElse(null);
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

        VideoAnalysisEntity saved;
        try {
            saved = analysisRepository.save(row.build());
        } catch (DataIntegrityViolationException e) {
            // 다른 서버(배포 중 두 대)나 연달아 누른 재분석이 먼저 자리를 차지했다.
            log.info("같은 영상에 진행 중인 영상 분석이 있어 새로 만들지 않습니다. recordingId={}", video.getRecordingId());
            return null;
        }
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
            case "completed" -> {
                if (!callbackHandler.applyRecovered(analysis.getAnalysisId(), job.getResult())) {
                    fail(analysis, "INVALID_RESULT", null, "작업 조회로 받은 결과가 검증을 통과하지 못했습니다.");
                }
            }
            case "failed" -> failFromJob(analysis, job.getError());
            default -> waitOrGiveUp(analysis, "작업이 아직 끝나지 않았습니다. status=" + status);
        }
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

    private void failFromJob(VideoAnalysisEntity analysis, Map<String, Object> error) {
        Object code = error == null ? null : error.get("code");
        Object retryable = error == null ? null : error.get("retryable");
        Object message = error == null ? null : error.get("message");
        fail(analysis, code == null ? "UNKNOWN" : String.valueOf(code),
                retryable instanceof Boolean flag ? flag : null,
                message == null ? "분석 서버가 작업 실패를 알렸습니다." : String.valueOf(message));
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
        if (open(video, failed.getChainId(), failed.getRequestedBy()) != null) {
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
