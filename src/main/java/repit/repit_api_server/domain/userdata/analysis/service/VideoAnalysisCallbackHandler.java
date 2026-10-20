package repit.repit_api_server.domain.userdata.analysis.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import repit.repit_api_server.domain.userdata.analysis.behavior.JsonValues;
import repit.repit_api_server.domain.userdata.analysis.behavior.VideoResultDocument;
import repit.repit_api_server.domain.userdata.analysis.behavior.VideoResultException;
import repit.repit_api_server.domain.userdata.analysis.entity.UnmatchedCallbackEntity;
import repit.repit_api_server.domain.userdata.analysis.entity.UnmatchedCallbackEntity.Reason;
import repit.repit_api_server.domain.userdata.analysis.entity.VideoAnalysisEntity;
import repit.repit_api_server.domain.userdata.analysis.entity.enums.VideoAnalysisStatus;
import repit.repit_api_server.domain.userdata.analysis.repository.UnmatchedCallbackRepository;
import repit.repit_api_server.domain.userdata.analysis.repository.VideoAnalysisRepository;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 영상 분석 결과(콜백, 또는 작업 조회로 받은 같은 본문)를 검증해 저장한다.
 *
 * <p>정상 결과로 반영하기 전에 이 서버가 보낸 요청의 결과인지 대조한다 — 요청 id로 찾은 행의 세션·면접·사용자·영상
 * (result.videoId), 그리고 이미 적어 둔 작업 id. failed는 결과 문서가 없어 영상은 대조하지 않는다. 하나라도 다르거나
 * 필수 값이 빠졌으면 원문을 {@code unmatched_callback}에 따로 남기고 행은 건드리지 않는다. 잘못 이어 붙인
 * 결과는 남의 면접에 보이고, 되돌릴 단서도 남지 않는다.
 *
 * <p>그다음 문서 전체를 {@link VideoResultDocument}로 검증한다 — 포장 상태의 일치, 공개 오류와 retryable, 행동 데이터의
 * 타입·의미, 영상·길이 묶음. 콜백과 작업 조회 복구가 같은 검증을 거친다. 통과하지 못한 문서도 원문을 따로 남긴다.
 *
 * <p>면접이 결과를 받을 자격이 있는지는 저장된 면접 상태로 본다. 중단·텍스트 면접의 결과는 반영하지 않고 기다리던 요청을
 * 정책 사유로 닫는다(분석 서버에는 정상 수신으로 답한다). 아직 진행 중인 면접이면 결과를 반영하지도 닫지도 않는다 — 완료되면
 * 작업 조회로 다시 받는다.
 *
 * <p>요청 행을 잠그고 반영한다. 같은 요청에 이미 저장한 종료 문서가 있으면 의미가 같은 문서(키 순서만 다른 것 포함)는 그대로
 * 두고, 다른 문서는 처음 것을 유지하며 따로 남긴다 — ready가 늦게 온 failed로 바뀌지 않는다. 이 서버가 결과 없이 닫은 행
 * (접수 미확인·처리 상한 초과)은 원격 종료 문서가 없으므로 늦게 온 첫 결과를 받는다.
 *
 * <p>따로 남기는 데 성공하면 예외 없이 끝나 2xx로 답한다. 4xx로 답하면 분석 서버가 재전송을 멈추고, 같은
 * 원문을 계속 받아 봐야 다시 반영되지 않는다. 저장 자체가 실패하면 예외가 올라가 5xx가 되고 분석 서버가 다시 보낸다.
 */
@Service
@RequiredArgsConstructor
public class VideoAnalysisCallbackHandler {

    private static final Logger log = LoggerFactory.getLogger(VideoAnalysisCallbackHandler.class);

    static final String KIND = "VIDEO";
    /** 결과 본문 상한(UTF-8 바이트). 분석 서버의 결과 문서 상한과 같다. 넘는 본문은 원문을 남기지 않는다. */
    public static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final List<String> ID_FIELDS = List.of("jobId", "requestId", "sessionId", "interviewId", "userId");
    // unmatched_callback의 request_id·job_id 컬럼 길이. 넘는 값을 그대로 넣으면 따로 남기는 저장까지 실패한다.
    private static final int MAX_ID_COLUMN = 255;

    /** 결과 하나를 받아 어떻게 처리했는지. */
    public enum Outcome {
        /** 요청 행에 결과를 저장했다. */
        APPLIED,
        /** 이미 저장한 것과 의미가 같은 문서라 아무것도 바꾸지 않았다. */
        DUPLICATE,
        /** 이미 저장한 것과 다른 종료 문서다. 처음 것을 두고 원문을 따로 남겼다. */
        CONFLICT,
        /** 식별자·형식·계약이 맞지 않아 원문을 따로 남겼다. */
        INVALID,
        /** 중단·텍스트 면접이라 반영하지 않았다. 기다리던 요청이면 정책 사유로 닫았다. */
        POLICY_CLOSED,
        /** 면접이 아직 끝나지 않아 반영도 닫지도 않았다. */
        POLICY_HELD
    }

    private final VideoAnalysisRepository analysisRepository;
    private final UnmatchedCallbackRepository unmatchedRepository;
    private final InterviewRepository interviewRepository;
    private final ObjectMapper objectMapper;

    /** 분석 서버가 보낸 콜백 원문. 컨트롤러가 인증과 크기 상한을 거친 뒤 UTF-8로 읽어 넘긴다. */
    @Transactional
    public Outcome handle(String rawBody) {
        String raw = Objects.requireNonNullElse(rawBody, "");
        Map<String, Object> body = parse(raw);
        if (body == null) {
            keepAside(Reason.INVALID_BODY, null, null, "JSON 객체가 아닙니다.", raw);
            return Outcome.INVALID;
        }
        return apply(body, raw, null);
    }

    /** UTF-8로 읽을 수 없는 콜백 본문. 깨진 바이트를 대체 문자로 바꾼 원문만 따로 남긴다. */
    @Transactional
    public void keepUndecodable(String lossyBody) {
        keepAside(Reason.INVALID_BODY, null, null, "UTF-8로 읽을 수 없는 본문입니다.", lossyBody);
    }

    /**
     * 작업 조회로 받은 결과. 콜백과 같은 검증을 거치고, 조회한 그 요청의 결과인지까지 본다.
     */
    @Transactional
    public Outcome applyRecovered(Long analysisId, Map<String, Object> body) {
        if (body == null) {
            keepAside(Reason.INVALID_BODY, null, null, "작업 조회가 끝났다는데 결과가 비어 있습니다. analysisId=" + analysisId, "");
            return Outcome.INVALID;
        }
        String raw = toJson(body);
        if (raw.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            // 상한을 넘는 원문은 DB에 남기지 않는다. 무엇이 왔는지는 식별자와 크기로만 적는다.
            keepAside(Reason.INVALID_BODY, idText(body, "requestId"), idText(body, "jobId"),
                    "작업 조회 결과가 " + MAX_BODY_BYTES + "바이트를 넘어 반영하지 않았습니다. analysisId=" + analysisId, "");
            return Outcome.INVALID;
        }
        return apply(body, raw, analysisId);
    }

    private Outcome apply(Map<String, Object> body, String raw, Long expectedAnalysisId) {
        String requestId = idText(body, "requestId");
        String jobId = idText(body, "jobId");

        // NUL은 PostgreSQL이 받지 못한다. 식별자에 섞여 오면 행을 찾는 쿼리부터 실패해 같은 본문이 끝없이 5xx로 되돌아온다.
        if (JsonValues.containsNul(body)) {
            keepAside(Reason.INVALID_BODY, requestId, jobId, "문자열에 NUL 문자가 있습니다.", raw);
            return Outcome.INVALID;
        }
        String invalid = invalidEnvelope(body);
        if (invalid != null) {
            keepAside(Reason.INVALID_BODY, requestId, jobId, invalid, raw);
            return Outcome.INVALID;
        }
        VideoAnalysisEntity analysis = analysisRepository.findLockedByRequestId(requestId).orElse(null);
        if (analysis == null) {
            keepAside(Reason.UNKNOWN_REQUEST, requestId, jobId, "이 서버가 보낸 적 없는 요청 id입니다.", raw);
            return Outcome.INVALID;
        }
        String mismatch = mismatch(analysis, body, jobId, expectedAnalysisId);
        if (mismatch != null) {
            keepAside(Reason.ID_MISMATCH, requestId, jobId, mismatch + " analysisId=" + analysis.getAnalysisId(), raw);
            return Outcome.INVALID;
        }

        VideoAnalysisEligibility.Verdict verdict = VideoAnalysisEligibility.of(
                interviewRepository.findById(analysis.getInterviewId()).orElse(null));
        if (verdict.kind() == VideoAnalysisEligibility.Kind.HOLD) {
            log.info("면접이 아직 끝나지 않아 영상 분석 결과를 반영하지 않고 둡니다. 완료되면 작업 조회로 다시 받습니다. "
                    + "analysisId={}, requestId={}", analysis.getAnalysisId(), requestId);
            return Outcome.POLICY_HELD;
        }
        if (verdict.kind() == VideoAnalysisEligibility.Kind.SKIP) {
            closeForPolicy(analysis, verdict);
            return Outcome.POLICY_CLOSED;
        }

        VideoResultDocument.Accepted accepted;
        try {
            accepted = VideoResultDocument.validate(body, String.valueOf(analysis.getRecordingId()));
        } catch (VideoResultException e) {
            keepAside(Reason.INVALID_BODY, requestId, jobId,
                    "결과 문서가 계약에 맞지 않습니다. " + e.getMessage() + " analysisId=" + analysis.getAnalysisId(), raw);
            return Outcome.INVALID;
        }

        if (analysis.getResult() != null) {
            if (JsonValues.same(analysis.getResult(), body)) {
                log.info("이미 저장한 영상 분석 결과와 같은 문서라 그대로 둡니다. analysisId={}, requestId={}",
                        analysis.getAnalysisId(), requestId);
                return Outcome.DUPLICATE;
            }
            keepAside(Reason.INVALID_BODY, requestId, jobId,
                    "이미 저장한 종료 문서와 다른 문서입니다. 처음 저장한 결과를 유지합니다. analysisId=" + analysis.getAnalysisId(), raw);
            return Outcome.CONFLICT;
        }

        // 원격 종료 문서가 아직 없는 행이다. 기다리는 중(PENDING)이거나, 이 서버가 결과 없이 닫았다(FAILED).
        VideoAnalysisStatus previous = analysis.getStatus();
        VideoAnalysisStatus status = VideoAnalysisStatus.fromResult(accepted.status());
        analysis.setJobId(jobId);
        analysis.setStatus(status);
        // failed 문서도 원문을 남긴다. 결과 없이 이 서버가 닫은 행과 분석 서버가 끝낸 행을 가르는 단서다. 화면의 호환용
        // result에는 완료(ready/partial/unavailable) 문서만 쓴다.
        analysis.setResult(body);
        analysis.setErrorCode(accepted.errorCode());
        analysis.setErrorRetryable(accepted.errorRetryable());
        analysis.setErrorMessage(accepted.errorMessage());
        analysis.setNextCheckAt(null);
        analysisRepository.save(analysis);
        if (previous != VideoAnalysisStatus.PENDING) {
            log.warn("결과 없이 닫았던 영상 분석 요청에 늦게 온 첫 결과를 저장했습니다. analysisId={}, requestId={}, "
                    + "닫을 때 사유={}, status={}", analysis.getAnalysisId(), requestId, previous, status);
        }
        log.info("영상 분석 결과를 저장했습니다. analysisId={}, requestId={}, jobId={}, status={}",
                analysis.getAnalysisId(), requestId, jobId, status);
        return Outcome.APPLIED;
    }

    /** 결과를 받을 수 없는 면접이다. 기다리던 요청이면 정책 사유로 닫고, 이미 끝난 행은 건드리지 않는다. */
    private void closeForPolicy(VideoAnalysisEntity analysis, VideoAnalysisEligibility.Verdict verdict) {
        if (analysis.getStatus() == VideoAnalysisStatus.PENDING) {
            analysis.setStatus(VideoAnalysisStatus.FAILED);
            analysis.setErrorCode(verdict.code());
            analysis.setErrorRetryable(false);
            analysis.setErrorMessage(verdict.message());
            analysis.setNextCheckAt(null);
            analysisRepository.save(analysis);
        }
        log.info("분석 대상이 아닌 면접이라 영상 분석 결과를 반영하지 않습니다. analysisId={}, 사유={}",
                analysis.getAnalysisId(), verdict.code());
    }

    /** 식별자는 공백뿐이 아닌 문자열이어야 한다. 숫자로 온 id는 계약 위반이다. */
    private static String invalidField(Map<String, Object> body, String field) {
        Object value = body.get(field);
        return value instanceof String text && !text.isBlank() ? null : "필수 필드 " + field + "가 없거나 문자열이 아닙니다.";
    }

    /** 식별자·스키마 버전·상태값 가운데 맞지 않는 것. 모두 맞으면 null. */
    private static String invalidEnvelope(Map<String, Object> body) {
        for (String field : ID_FIELDS) {
            String invalid = invalidField(body, field);
            if (invalid != null) {
                return invalid;
            }
        }
        if (!VideoResultDocument.SCHEMA_VERSION.equals(body.get("schemaVersion"))) {
            return "지원하지 않는 스키마 버전입니다: " + clip(String.valueOf(body.get("schemaVersion")));
        }
        Object status = body.get("status");
        if (!(status instanceof String) || !VideoResultDocument.STATUSES.contains(status)) {
            return "알 수 없는 결과 상태입니다: " + clip(String.valueOf(status));
        }
        if (!"failed".equals(status)) {
            String videoId = videoId(body);
            if (videoId == null || videoId.isBlank()) {
                return "필수 필드 result.videoId가 없거나 문자열이 아닙니다.";
            }
        }
        return null;
    }

    /** 이 요청의 결과가 아닌 이유. 맞으면 null. 보낸 값과 글자 그대로 같아야 한다. */
    private String mismatch(VideoAnalysisEntity analysis, Map<String, Object> body, String jobId,
                            Long expectedAnalysisId) {
        if (expectedAnalysisId != null && !expectedAnalysisId.equals(analysis.getAnalysisId())) {
            return "조회한 요청(" + expectedAnalysisId + ")과 결과의 요청이 다릅니다.";
        }
        if (!Objects.equals(analysis.getSessionId(), body.get("sessionId"))) {
            return "세션이 다릅니다.";
        }
        if (!String.valueOf(analysis.getInterviewId()).equals(body.get("interviewId"))) {
            return "면접이 다릅니다.";
        }
        if (!String.valueOf(analysis.getUserId()).equals(body.get("userId"))) {
            return "사용자가 다릅니다.";
        }
        if (!"failed".equals(body.get("status")) && !String.valueOf(analysis.getRecordingId()).equals(videoId(body))) {
            return "영상이 다릅니다.";
        }
        if (analysis.getJobId() != null && !analysis.getJobId().equals(jobId)) {
            return "작업 id가 다릅니다. 저장된 값=" + analysis.getJobId();
        }
        VideoAnalysisEntity byJob = analysisRepository.findByJobId(jobId).orElse(null);
        if (byJob != null && !byJob.getAnalysisId().equals(analysis.getAnalysisId())) {
            return "요청 id와 작업 id가 서로 다른 요청을 가리킵니다. 작업 id의 요청=" + byJob.getAnalysisId();
        }
        return null;
    }

    private void keepAside(Reason reason, String requestId, String jobId, String detail, String raw) {
        unmatchedRepository.save(UnmatchedCallbackEntity.builder()
                .kind(KIND)
                .reason(reason)
                .requestId(clipId(requestId))
                .jobId(clipId(jobId))
                .detail(withoutNul(detail))
                // PostgreSQL text에는 NUL을 넣을 수 없다. 남기지 못하면 같은 본문이 끝없이 5xx로 되돌아온다.
                .payload(withoutNul(raw))
                .build());
        log.error("영상 분석 결과를 반영하지 않고 따로 남겼습니다. reason={}, requestId={}, jobId={}, detail={}",
                reason, clipId(requestId), clipId(jobId), withoutNul(detail));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String raw) {
        if (raw.isBlank()) {
            return null;
        }
        try {
            Object value = objectMapper.readValue(raw, Object.class);
            return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
        } catch (JacksonException e) {
            return null;
        }
    }

    private String toJson(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JacksonException e) {
            return String.valueOf(body);
        }
    }

    /** 영상 결과 문서(result)의 videoId. failed는 문서가 없어 null이다. */
    private static String videoId(Map<String, Object> body) {
        return body.get("result") instanceof Map<?, ?> result && result.get("videoId") instanceof String id ? id : null;
    }

    /** 기록·로그에 남길 식별자. 문자열이 아니면 남기지 않는다. */
    private static String idText(Map<String, Object> body, String field) {
        return body.get(field) instanceof String text ? text : null;
    }

    private static String clipId(String value) {
        return value == null || value.length() <= MAX_ID_COLUMN ? withoutNul(value) : withoutNul(value.substring(0, MAX_ID_COLUMN));
    }

    private static String clip(String value) {
        return value.length() <= 100 ? value : value.substring(0, 100) + "…";
    }

    private static String withoutNul(String value) {
        return value == null ? null : value.replace('\u0000', '�');
    }
}
