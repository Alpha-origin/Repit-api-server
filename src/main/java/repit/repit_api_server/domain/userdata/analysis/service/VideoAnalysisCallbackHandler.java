package repit.repit_api_server.domain.userdata.analysis.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import repit.repit_api_server.domain.userdata.analysis.entity.UnmatchedCallbackEntity;
import repit.repit_api_server.domain.userdata.analysis.entity.UnmatchedCallbackEntity.Reason;
import repit.repit_api_server.domain.userdata.analysis.entity.VideoAnalysisEntity;
import repit.repit_api_server.domain.userdata.analysis.entity.enums.VideoAnalysisStatus;
import repit.repit_api_server.domain.userdata.analysis.repository.UnmatchedCallbackRepository;
import repit.repit_api_server.domain.userdata.analysis.repository.VideoAnalysisRepository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 영상 분석 결과(콜백, 또는 작업 조회로 받은 같은 본문)를 검증해 저장한다.
 *
 * <p>정상 결과로 반영하기 전에 이 서버가 보낸 요청의 결과인지 대조한다 — 요청 id로 찾은 행의 세션·면접·사용자·영상,
 * 그리고 이미 적어 둔 작업 id. 하나라도 다르거나 필수 값이 빠졌으면 원문을 {@code unmatched_callback}에 따로 남기고
 * 행은 건드리지 않는다. 잘못 이어 붙인 결과는 남의 면접에 보이고, 되돌릴 단서도 남지 않는다.
 *
 * <p>따로 남기는 데 성공하면 예외 없이 끝나 2xx로 답한다. 4xx로 답하면 분석 서버가 재전송을 멈추고, 같은
 * 원문을 계속 받아 봐야 다시 반영되지 않는다. 저장 자체가 실패하면 예외가 올라가 5xx가 되고 분석 서버가 다시 보낸다.
 */
@Service
@RequiredArgsConstructor
public class VideoAnalysisCallbackHandler {

    private static final Logger log = LoggerFactory.getLogger(VideoAnalysisCallbackHandler.class);

    static final String KIND = "VIDEO";
    // 이 서버가 읽을 줄 아는 결과 스키마. 추가된 선택 필드는 원문과 함께 저장될 뿐이다.
    static final String SCHEMA_VERSION = "1";
    private static final List<String> REQUIRED =
            List.of("jobId", "requestId", "sessionId", "interviewId", "userId", "videoId", "status", "schemaVersion");

    private final VideoAnalysisRepository analysisRepository;
    private final UnmatchedCallbackRepository unmatchedRepository;
    private final ObjectMapper objectMapper;

    /** 분석 서버가 보낸 콜백 원문. */
    @Transactional
    public void handle(String rawBody) {
        String raw = Objects.requireNonNullElse(rawBody, "");
        Map<String, Object> body = parse(raw);
        if (body == null) {
            keepAside(Reason.INVALID_BODY, null, null, "JSON 객체가 아닙니다.", raw);
            return;
        }
        apply(body, raw, null);
    }

    /**
     * 작업 조회로 받은 결과. 콜백과 같은 검증을 거치고, 조회한 그 요청의 결과인지까지 본다.
     *
     * @return 정상 결과로 반영했으면 true
     */
    @Transactional
    public boolean applyRecovered(Long analysisId, Map<String, Object> body) {
        if (body == null) {
            keepAside(Reason.INVALID_BODY, null, null, "작업 조회가 completed인데 결과가 비어 있습니다. analysisId=" + analysisId, "");
            return false;
        }
        return apply(body, toJson(body), analysisId);
    }

    private boolean apply(Map<String, Object> body, String raw, Long expectedAnalysisId) {
        String requestId = text(body, "requestId");
        String jobId = text(body, "jobId");

        String invalid = invalidField(body);
        if (invalid != null) {
            keepAside(Reason.INVALID_BODY, requestId, jobId, invalid, raw);
            return false;
        }
        VideoAnalysisEntity analysis = analysisRepository.findByRequestId(requestId).orElse(null);
        if (analysis == null) {
            keepAside(Reason.UNKNOWN_REQUEST, requestId, jobId, "이 서버가 보낸 적 없는 요청 id입니다.", raw);
            return false;
        }
        String mismatch = mismatch(analysis, body, jobId, expectedAnalysisId);
        if (mismatch != null) {
            keepAside(Reason.ID_MISMATCH, requestId, jobId, mismatch + " analysisId=" + analysis.getAnalysisId(), raw);
            return false;
        }

        VideoAnalysisStatus status = VideoAnalysisStatus.fromResult(text(body, "status"));
        Map<?, ?> error = body.get("error") instanceof Map<?, ?> map ? map : null;
        analysis.setJobId(jobId);
        analysis.setStatus(status);
        analysis.setResult(body);
        analysis.setErrorCode(error == null ? null : textOf(error.get("code")));
        analysis.setErrorRetryable(error != null && error.get("retryable") instanceof Boolean retryable ? retryable : null);
        analysis.setErrorMessage(error == null ? null : textOf(error.get("message")));
        analysis.setNextCheckAt(null);
        analysisRepository.save(analysis);
        log.info("영상 분석 결과를 저장했습니다. analysisId={}, requestId={}, jobId={}, status={}",
                analysis.getAnalysisId(), requestId, jobId, status);
        return true;
    }

    /** 필수 필드·상태값·스키마 버전 가운데 맞지 않는 것. 모두 맞으면 null. */
    private static String invalidField(Map<String, Object> body) {
        for (String field : REQUIRED) {
            String value = text(body, field);
            if (value == null || value.isBlank()) {
                return "필수 필드 " + field + "가 없습니다.";
            }
        }
        if (VideoAnalysisStatus.fromResult(text(body, "status")) == null) {
            return "알 수 없는 결과 상태입니다: " + text(body, "status");
        }
        if (!SCHEMA_VERSION.equals(text(body, "schemaVersion"))) {
            return "지원하지 않는 스키마 버전입니다: " + text(body, "schemaVersion");
        }
        return null;
    }

    /** 이 요청의 결과가 아닌 이유. 맞으면 null. */
    private String mismatch(VideoAnalysisEntity analysis, Map<String, Object> body, String jobId,
                            Long expectedAnalysisId) {
        if (expectedAnalysisId != null && !expectedAnalysisId.equals(analysis.getAnalysisId())) {
            return "조회한 요청(" + expectedAnalysisId + ")과 결과의 요청이 다릅니다.";
        }
        if (!Objects.equals(analysis.getSessionId(), text(body, "sessionId"))) {
            return "세션이 다릅니다.";
        }
        if (!String.valueOf(analysis.getInterviewId()).equals(text(body, "interviewId"))) {
            return "면접이 다릅니다.";
        }
        if (!String.valueOf(analysis.getUserId()).equals(text(body, "userId"))) {
            return "사용자가 다릅니다.";
        }
        if (!String.valueOf(analysis.getRecordingId()).equals(text(body, "videoId"))) {
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
                .requestId(requestId)
                .jobId(jobId)
                .detail(detail)
                .payload(raw)
                .build());
        log.error("영상 분석 결과를 반영하지 않고 따로 남겼습니다. reason={}, requestId={}, jobId={}, detail={}",
                reason, requestId, jobId, detail);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String raw) {
        if (raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, Map.class);
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

    /** id는 문자열로 오지만 숫자로 와도 같은 값으로 본다. 객체·배열은 값이 없는 것으로 본다. */
    private static String text(Map<String, Object> body, String field) {
        return textOf(body.get(field));
    }

    private static String textOf(Object value) {
        if (value instanceof String text) {
            return text.trim();
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return null;
    }
}
