package repit.repit_api_server.domain.userdata.analysis.behavior;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 행동 관찰 v1의 고정 값. 분석 서버 {@code app/core/common/behavior/policy.py}와 같아야 한다.
 *
 * <p>값을 바꾸면 같은 버전 이름으로 다른 결과가 나온다. 임계값·모델이 바뀌면 분석 서버가 버전을 올리고, 이 서버는 모르는
 * 버전을 받지 않는다.
 */
final class BehaviorPolicy {

    static final String SCHEMA_VERSION = "behavior-v1";
    static final String MODEL_VERSION = "mp-face-pose-v1";
    static final String THRESHOLDS_VERSION = "behavior-policy-v1";

    static final int SAMPLE_MS = 200;
    static final int MIN_DURATION_MS = 10_000;
    static final double MIN_COVERAGE = 0.30;
    static final double READY_COVERAGE = 0.80;
    static final double READY_SAMPLE_COVERAGE = 0.90;
    static final int MIN_EVENT_MS = 2_000;
    static final int MAX_EVENTS = 60;
    static final int MAX_BINS = 60;
    static final int MAX_DURATION_MS = 3_600_000;
    static final int MAX_SAMPLED_FRAMES = 18_000;
    static final int MAX_SUMMARY = 3;
    static final int MAX_SUMMARY_CHARS = 200;
    static final int MAX_WARNINGS = 8;
    static final int MAX_MODEL_VERSION_CHARS = 100;
    static final int MAX_VIDEO_ID_CHARS = 200;
    // 비율은 소수점 6자리로 반올림해 온다. 이 차이까지는 같은 값으로 본다.
    static final double ROUNDING_TOLERANCE = 1e-6;

    static final String FACE = "faceVisibleRatio";
    static final String HEAD = "headFacingRatio";
    static final String SHOULDER = "shoulderLevelRatio";
    static final String UPPER_BODY = "upperBodyStableRatio";
    static final String SMILE = "smileShapeRatio";
    static final List<String> KEYS = List.of(FACE, HEAD, SHOULDER, UPPER_BODY, SMILE);

    /** 이벤트 종류 → 그 이벤트가 깎아 먹는 지표. 미소는 이벤트가 없다. */
    static final Map<String, String> EVENT_METRICS;

    static {
        Map<String, String> events = new LinkedHashMap<>();
        events.put("FACE_NOT_VISIBLE", FACE);
        events.put("HEAD_TURNED", HEAD);
        events.put("SHOULDER_TILTED", SHOULDER);
        events.put("UPPER_BODY_MOVEMENT", UPPER_BODY);
        EVENT_METRICS = Map.copyOf(events);
    }

    static final Set<String> WARNINGS = Set.of("LOW_SAMPLE_COVERAGE", "MULTIPLE_PEOPLE", "FACE_TOO_SMALL",
            "LOW_FACE_COVERAGE", "LOW_POSE_COVERAGE", "COMPONENT_FAILURE", "EVENTS_TRUNCATED");

    static final String AVAILABLE = "AVAILABLE";
    static final String UNAVAILABLE = "UNAVAILABLE";

    private BehaviorPolicy() {
    }

    /** 분자/분모를 소수점 6자리에서 반올림(ROUND_HALF_UP). 분모가 0이면 0. */
    static double fraction(long numerator, long denominator) {
        if (denominator == 0) {
            return 0.0;
        }
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP).doubleValue();
    }

    /** 시간 요약 구간 하나의 폭. 최대 60개가 되도록 1초 단위로 넓히고, 10초보다 좁히지 않는다. */
    static int binWidth(int durationMs) {
        return Math.max(10_000, ((durationMs + 59_999) / 60_000) * 1_000);
    }

    /** 지표를 측정할 수 있는지. 전체 10초 이상, 지표 분모 10초 이상, 분모가 전체의 30% 이상. */
    static boolean available(long denominatorMs, long durationMs) {
        return durationMs >= MIN_DURATION_MS && denominatorMs >= MIN_DURATION_MS
                && (double) denominatorMs / durationMs >= MIN_COVERAGE;
    }
}
