package repit.repit_api_server.domain.userdata.analysis.behavior;

import java.util.Map;

import static repit.repit_api_server.domain.userdata.analysis.behavior.StrictJson.*;

/**
 * 영상 분석 공개 오류 사전.
 *
 * <p>분석 서버 코드는 {@code app/core/common/video/errors.py}와 같다. retryable은 "같은 영상을 새 요청 id로 다시 요청하면
 * 나을 수 있는지"다. 같은 원본의 측정 부족(BEHAVIOR_NOT_MEASURABLE)은 다시 해도 같으므로 false다. 분석 서버가 이 표와 다른
 * retryable을 보내면 계약이 깨진 것으로 보고 결과로 받지 않는다.
 *
 * <p>이 서버가 결과 없이 닫은 사유(접수 미확인, 작업 없음, 처리 상한 초과 등)도 화면용 코드로 옮긴다. 그 밖의 내부 코드는
 * UNKNOWN_ERROR로만 내보낸다 — DB에 적힌 임의 문자열을 화면 코드로 쓰지 않는다.
 *
 * <p>화면의 {@code error.retryable}과 재분석 허용(canRetry)은 같은 판단({@link #userRetryable})을 쓴다. 공개 오류는
 * retryable=false라고 내보내면서 재분석 버튼은 열리는 어긋남을 만들지 않는다.
 */
public final class VideoPublicErrors {

    public static final String UNKNOWN_ERROR = "UNKNOWN_ERROR";

    private static final Map<String, VideoPublicError> ANALYSIS = Map.ofEntries(
            entry("SOURCE_ACCESS_DENIED_OR_EXPIRED", "영상 파일에 접근할 수 없습니다. 새 다운로드 URL 로 다시 요청해 주세요.", true),
            entry("SOURCE_DOWNLOAD_FAILED", "영상 파일을 내려받지 못했습니다.", true),
            entry("EXTERNAL_SERVICE_UNAVAILABLE", "외부 서비스를 일시적으로 사용할 수 없습니다.", true),
            entry("PROCESSING_TIMEOUT", "영상 처리 시간이 제한을 넘었습니다.", true),
            entry("SOURCE_NOT_FOUND", "영상 파일을 찾을 수 없습니다.", false),
            entry("SOURCE_SIZE_MISMATCH", "영상 파일 크기가 요청한 크기와 다릅니다.", false),
            entry("VIDEO_LIMIT_EXCEEDED", "영상의 크기·길이·해상도·프레임률 제한을 넘었습니다.", false),
            entry("VIDEO_FORMAT_UNSUPPORTED", "지원하지 않는 영상 형식입니다. WebM(VP8/VP9) 또는 MP4(H.264)만 지원합니다.", false),
            entry("VIDEO_DECODE_FAILED", "영상 파일이 손상되었거나 해석할 수 없습니다.", false),
            entry("INTERNAL_ERROR", "영상 분석 서버 내부 오류가 발생했습니다.", false),
            entry("ANALYZER_NOT_CONFIGURED", "영상 행동 분석기가 아직 연결되지 않았습니다.", false),
            entry("BEHAVIOR_NOT_MEASURABLE", "이 영상에서는 행동 지표를 측정할 수 있는 구간이 충분하지 않습니다.", false));

    // 이 서버가 결과 없이 닫은 사유의 문구. 코드 값은 VideoAnalysisService가 error_code에 남기는 것과 같다. 다시 해 볼 수
    // 있는지는 닫을 때 남긴 error_retryable을 따른다(비어 있으면 사용자 재분석은 받는다).
    private static final Map<String, String> SERVICE = Map.of(
            "NOT_ACKNOWLEDGED", "분석 서버가 영상 분석 요청을 접수했는지 확인하지 못했습니다.",
            "JOB_NOT_FOUND", "분석 서버에서 영상 분석 작업을 찾지 못했습니다.",
            "PROCESSING_OVERDUE", "영상 분석이 제한 시간 안에 끝나지 않았습니다.",
            "INVALID_RESULT", "영상 분석 결과의 형식을 확인하지 못했습니다.",
            "RESULT_EXPIRED", "영상 분석 결과의 보관 기간이 지났습니다.");

    private static final String UNKNOWN_MESSAGE = "영상 분석을 마치지 못했습니다.";

    private VideoPublicErrors() {
    }

    /**
     * 화면에 내보낼 오류. 모르는 코드나 내부 사유는 UNKNOWN_ERROR다. retryable은 {@link #userRetryable}과 같다.
     *
     * @param storedCode      요청 행의 error_code
     * @param storedRetryable 요청 행의 error_retryable
     */
    public static VideoPublicError forDisplay(String storedCode, Boolean storedRetryable) {
        boolean retryable = userRetryable(storedCode, storedRetryable);
        VideoPublicError known = storedCode == null ? null : ANALYSIS.get(storedCode);
        if (known != null) {
            return known;
        }
        String message = storedCode == null ? null : SERVICE.get(storedCode);
        return message == null
                ? new VideoPublicError(UNKNOWN_ERROR, UNKNOWN_MESSAGE, retryable)
                : new VideoPublicError(storedCode, message, retryable);
    }

    /**
     * 결과 없이 끝난 요청을 같은 원본으로 다시 분석해 볼 만한지. 분석 서버의 공개 코드면 공개 사전을 따른다. 이 서버가 닫은
     * 사유면 닫을 때 남긴 값을 따른다 — false면 막고, 비어 있거나 true면 받는다.
     */
    public static boolean userRetryable(String storedCode, Boolean storedRetryable) {
        Boolean known = retryableOf(storedCode);
        if (known != null) {
            return known;
        }
        return !Boolean.FALSE.equals(storedRetryable);
    }

    /** 분석 서버가 알릴 수 있는 공개 코드인지. */
    public static boolean isAnalysisCode(String code) {
        return code != null && ANALYSIS.containsKey(code);
    }

    /** 분석 서버 공개 코드의 retryable. 모르는 코드면 null. */
    public static Boolean retryableOf(String code) {
        VideoPublicError known = code == null ? null : ANALYSIS.get(code);
        return known == null ? null : known.retryable();
    }

    /**
     * 분석 서버가 보낸 오류 객체를 검증한다. 정확히 code·message·retryable만 있어야 하고, 코드는 공개 사전에 있어야 하며,
     * retryable은 엄격한 불리언으로 사전 값과 같아야 한다. 메시지는 1~1000자 문자열이면 받되 화면에는 쓰지 않는다.
     */
    public static String validateRemote(Object raw, String path) {
        Map<String, Object> error = object(raw, path);
        exactKeys(error, java.util.List.of("code", "message", "retryable"), path);
        Object code = error.get("code");
        require(code instanceof String && ANALYSIS.containsKey(code), path + ".code", "unknown public error code");
        text(error.get("message"), 1000, path + ".message");
        boolean retryable = bool(error.get("retryable"), path + ".retryable");
        require(retryable == ANALYSIS.get(code).retryable(), path + ".retryable", "retryable mismatch");
        return (String) code;
    }

    private static Map.Entry<String, VideoPublicError> entry(String code, String message, boolean retryable) {
        return Map.entry(code, new VideoPublicError(code, message, retryable));
    }
}
