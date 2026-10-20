package repit.repit_api_server.domain.userdata.analysis.behavior;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static repit.repit_api_server.domain.userdata.analysis.behavior.BehaviorPolicy.*;
import static repit.repit_api_server.domain.userdata.analysis.behavior.StrictJson.*;

/**
 * 행동 관찰 문서(behavior-v1)를 타입과 의미까지 검증한다.
 *
 * <p>구조 스키마만으로는 끝내지 않는다. 비율과 분자·분모, 측정 범위(coverage), 상태·미측정 사유, 시간 구간의 경계와 합,
 * 이벤트의 순서·범위·길이·상한, 경고까지 분석 서버가 만든 규칙대로 서로 맞는지 본다 — 분석 서버
 * {@code app/core/common/behavior/validation.py}와 같은 규칙이다. 모양만 맞고 숫자가 서로 어긋난 결과를 저장하면 화면이
 * 거짓 수치를 그린다.
 *
 * <p>콜백과 작업 조회 복구, 저장된 결과를 다시 읽는 조회가 모두 이것 하나를 쓴다.
 */
public final class BehaviorValidator {

    private static final List<String> FIELDS = List.of("schemaVersion", "modelVersion", "thresholdsVersion", "videoId",
            "durationMs", "sampleIntervalMs", "sampledFrameCount", "sampledDurationMs", "metrics", "timeline", "events",
            "eventsTruncated", "summary", "warnings");
    private static final List<String> METRIC_FIELDS =
            List.of("status", "value", "numeratorMs", "denominatorMs", "coverageRatio", "unavailableReason");
    private static final List<String> PAIR_FIELDS = List.of("numeratorMs", "denominatorMs");
    private static final List<String> BIN_FIELDS = List.of("startMs", "endMs", "metrics");
    private static final List<String> EVENT_FIELDS = List.of("type", "sourceMetric", "startMs", "endMs");
    private static final Set<String> REASONS = Set.of("INSUFFICIENT_DURATION", "NO_SINGLE_FACE",
            "HEAD_POSE_UNAVAILABLE", "SHOULDERS_NOT_VISIBLE", "BLENDSHAPES_UNAVAILABLE", "LOW_COVERAGE");

    private BehaviorValidator() {
    }

    /**
     * 검증을 통과한 문서. 통과하지 못하면 어긋난 위치와 규칙을 담은 {@link VideoResultException}.
     *
     * @param raw Jackson이 타입 없이 읽은 {@code result.analysis.data}
     */
    public static BehaviorData parse(Object raw) {
        BehaviorData data = shape(raw);
        coherent(data);
        return data;
    }

    /** ready = 다섯 지표 모두 측정 가능하고 각 범위 80% 이상, 표본 범위 90% 이상, 구성요소 실패 없음. 아니면 partial. */
    public static String quality(BehaviorData data) {
        double duration = data.durationMs();
        boolean ready = data.metrics().values().stream()
                .allMatch(m -> AVAILABLE.equals(m.status()) && m.denominatorMs() / duration >= READY_COVERAGE)
                && data.sampledDurationMs() / duration >= READY_SAMPLE_COVERAGE
                && !data.warnings().contains("COMPONENT_FAILURE");
        return ready ? "ready" : "partial";
    }

    // ---- 타입·범위(JSON Schema) ----

    private static BehaviorData shape(Object raw) {
        Map<String, Object> d = object(raw, "$");
        exactKeys(d, FIELDS, "$");
        constant(d.get("schemaVersion"), SCHEMA_VERSION, "$.schemaVersion");
        text(d.get("modelVersion"), MAX_MODEL_VERSION_CHARS, "$.modelVersion");
        // 모델이 바뀌면 같은 지표 이름이라도 다른 값이 나온다. 이 서버가 아는 모델의 결과만 받는다.
        constant(d.get("modelVersion"), MODEL_VERSION, "$.modelVersion");
        constant(d.get("thresholdsVersion"), THRESHOLDS_VERSION, "$.thresholdsVersion");
        String videoId = text(d.get("videoId"), MAX_VIDEO_ID_CHARS, "$.videoId");
        int duration = (int) integer(d.get("durationMs"), 1, MAX_DURATION_MS, "$.durationMs");
        integer(d.get("sampleIntervalMs"), SAMPLE_MS, SAMPLE_MS, "$.sampleIntervalMs");
        int count = (int) integer(d.get("sampledFrameCount"), 0, MAX_SAMPLED_FRAMES, "$.sampledFrameCount");
        int sampled = (int) integer(d.get("sampledDurationMs"), 0, MAX_DURATION_MS, "$.sampledDurationMs");

        Map<String, Object> rawMetrics = object(d.get("metrics"), "$.metrics");
        exactKeys(rawMetrics, KEYS, "$.metrics");
        Map<String, BehaviorData.Metric> metrics = new LinkedHashMap<>();
        for (String key : KEYS) {
            metrics.put(key, metric(rawMetrics.get(key), "$.metrics." + key));
        }

        List<Object> rawBins = array(d.get("timeline"), 1, MAX_BINS, "$.timeline");
        List<BehaviorData.TimelineBin> timeline = new ArrayList<>();
        for (int i = 0; i < rawBins.size(); i++) {
            timeline.add(bin(rawBins.get(i), "$.timeline[" + i + "]"));
        }

        List<Object> rawEvents = array(d.get("events"), 0, MAX_EVENTS, "$.events");
        List<BehaviorData.Event> events = new ArrayList<>();
        for (int i = 0; i < rawEvents.size(); i++) {
            events.add(event(rawEvents.get(i), "$.events[" + i + "]"));
        }

        boolean truncated = bool(d.get("eventsTruncated"), "$.eventsTruncated");

        List<Object> rawSummary = array(d.get("summary"), 0, MAX_SUMMARY, "$.summary");
        List<String> summary = new ArrayList<>();
        for (int i = 0; i < rawSummary.size(); i++) {
            summary.add(text(rawSummary.get(i), MAX_SUMMARY_CHARS, "$.summary[" + i + "]"));
        }

        List<Object> rawWarnings = array(d.get("warnings"), 0, MAX_WARNINGS, "$.warnings");
        List<String> warnings = new ArrayList<>();
        for (int i = 0; i < rawWarnings.size(); i++) {
            String warning = text(rawWarnings.get(i), 40, "$.warnings[" + i + "]");
            require(WARNINGS.contains(warning), "$.warnings[" + i + "]", "unknown warning");
            warnings.add(warning);
        }

        return new BehaviorData(SCHEMA_VERSION, MODEL_VERSION, THRESHOLDS_VERSION, videoId, duration, SAMPLE_MS,
                count, sampled, metrics, timeline, events, truncated, summary, warnings);
    }

    private static BehaviorData.Metric metric(Object raw, String path) {
        Map<String, Object> m = object(raw, path);
        exactKeys(m, METRIC_FIELDS, path);
        Object rawStatus = m.get("status");
        require(AVAILABLE.equals(rawStatus) || UNAVAILABLE.equals(rawStatus), path + ".status", "unknown status");
        require(m.get("unavailableReason") == null || m.get("unavailableReason") instanceof String,
                path + ".unavailableReason", "string required");
        Double value = m.get("value") == null ? null : number(m.get("value"), 0, 1, path + ".value");
        int numerator = (int) integer(m.get("numeratorMs"), 0, MAX_DURATION_MS, path + ".numeratorMs");
        int denominator = (int) integer(m.get("denominatorMs"), 0, MAX_DURATION_MS, path + ".denominatorMs");
        require(numerator <= denominator, path, "numerator exceeds denominator");
        double coverage = number(m.get("coverageRatio"), 0, 1, path + ".coverageRatio");
        Object reason = m.get("unavailableReason");
        require(reason == null || REASONS.contains(reason), path + ".unavailableReason", "unknown reason");
        return new BehaviorData.Metric((String) rawStatus, value, numerator, denominator, coverage, (String) reason);
    }

    private static BehaviorData.Pair pair(Object raw, String path) {
        Map<String, Object> p = object(raw, path);
        exactKeys(p, PAIR_FIELDS, path);
        int numerator = (int) integer(p.get("numeratorMs"), 0, MAX_DURATION_MS, path + ".numeratorMs");
        int denominator = (int) integer(p.get("denominatorMs"), 0, MAX_DURATION_MS, path + ".denominatorMs");
        require(numerator <= denominator, path, "numerator exceeds denominator");
        return new BehaviorData.Pair(numerator, denominator);
    }

    private static BehaviorData.TimelineBin bin(Object raw, String path) {
        Map<String, Object> b = object(raw, path);
        exactKeys(b, BIN_FIELDS, path);
        int start = (int) integer(b.get("startMs"), 0, MAX_DURATION_MS, path + ".startMs");
        int end = (int) integer(b.get("endMs"), 1, MAX_DURATION_MS, path + ".endMs");
        Map<String, Object> rawPairs = object(b.get("metrics"), path + ".metrics");
        exactKeys(rawPairs, KEYS, path + ".metrics");
        Map<String, BehaviorData.Pair> pairs = new LinkedHashMap<>();
        for (String key : KEYS) {
            pairs.put(key, pair(rawPairs.get(key), path + ".metrics." + key));
        }
        return new BehaviorData.TimelineBin(start, end, pairs);
    }

    private static BehaviorData.Event event(Object raw, String path) {
        Map<String, Object> e = object(raw, path);
        exactKeys(e, EVENT_FIELDS, path);
        // 불변 Map은 null 키·값을 조회하면 NPE를 던진다. 문자열인지 먼저 본다.
        String type = text(e.get("type"), 40, path + ".type");
        require(EVENT_METRICS.containsKey(type), path + ".type", "unknown event type");
        String source = text(e.get("sourceMetric"), 40, path + ".sourceMetric");
        require(EVENT_METRICS.containsValue(source), path + ".sourceMetric", "unknown source metric");
        int start = (int) integer(e.get("startMs"), 0, MAX_DURATION_MS, path + ".startMs");
        int end = (int) integer(e.get("endMs"), 1, MAX_DURATION_MS, path + ".endMs");
        return new BehaviorData.Event(type, source, start, end);
    }

    // ---- 의미(분석 서버 validation.py) ----

    private static void coherent(BehaviorData data) {
        int duration = data.durationMs();
        int count = data.sampledFrameCount();
        int sampled = data.sampledDurationMs();
        require(count <= (duration + SAMPLE_MS - 1) / SAMPLE_MS, "$.sampledFrameCount", "sample count overflow");
        require((count == 0) == (sampled == 0), "$.sampledDurationMs", "sample count/duration mismatch");
        require(Math.max(0, (long) (count - 1) * SAMPLE_MS) <= sampled
                        && sampled <= Math.min(duration, (long) count * SAMPLE_MS),
                "$.sampledDurationMs", "slot weights mismatch");

        for (String key : KEYS) {
            metric(key, data);
        }
        require(data.metrics().values().stream().anyMatch(m -> AVAILABLE.equals(m.status())),
                "$.metrics", "empty successful result");
        timeline(data);
        events(data);
        warnings(data);
    }

    private static void metric(String key, BehaviorData data) {
        String path = "$.metrics." + key;
        BehaviorData.Metric metric = data.metrics().get(key);
        int duration = data.durationMs();
        int denominator = metric.denominatorMs();
        require(denominator <= data.sampledDurationMs(), path, "metric exceeds sampled duration");
        require(Math.abs(metric.coverageRatio() - fraction(denominator, duration)) <= ROUNDING_TOLERANCE,
                path + ".coverageRatio", "coverage mismatch");
        boolean measurable = available(denominator, duration);
        require(AVAILABLE.equals(metric.status()) == measurable, path + ".status", "metric status mismatch");
        if (measurable) {
            require(metric.value() != null && metric.unavailableReason() == null, path, "available metric missing value");
            require(Math.abs(metric.value() - fraction(metric.numeratorMs(), denominator)) <= ROUNDING_TOLERANCE,
                    path + ".value", "value mismatch");
        } else {
            require(metric.value() == null && metric.unavailableReason() != null, path,
                    "unavailable metric needs reason");
            require(expectedReason(key, denominator, data).equals(metric.unavailableReason()),
                    path + ".unavailableReason", "metric unavailable reason mismatch");
        }
        if (!FACE.equals(key)) {
            require(denominator <= data.metrics().get(FACE).numeratorMs(), path, "metric exceeds single-face duration");
        }
    }

    /** 사유는 짧은 영상 → 단일 얼굴 없음 → 그 모델의 출력 없음 → 관찰 범위 부족 순서로 정한다. */
    private static String expectedReason(String key, int denominator, BehaviorData data) {
        if (data.durationMs() < MIN_DURATION_MS) {
            return "INSUFFICIENT_DURATION";
        }
        if (!FACE.equals(key) && data.metrics().get(FACE).numeratorMs() == 0) {
            return "NO_SINGLE_FACE";
        }
        if (denominator == 0) {
            return switch (key) {
                case HEAD -> "HEAD_POSE_UNAVAILABLE";
                case SHOULDER, UPPER_BODY -> "SHOULDERS_NOT_VISIBLE";
                case SMILE -> "BLENDSHAPES_UNAVAILABLE";
                default -> "LOW_COVERAGE";
            };
        }
        return "LOW_COVERAGE";
    }

    private static void timeline(BehaviorData data) {
        int duration = data.durationMs();
        int width = binWidth(duration);
        List<BehaviorData.TimelineBin> bins = data.timeline();
        require(bins.size() == (duration + width - 1) / width, "$.timeline", "timeline size mismatch");
        Map<String, long[]> totals = new HashMap<>();
        KEYS.forEach(key -> totals.put(key, new long[2]));
        for (int i = 0; i < bins.size(); i++) {
            String path = "$.timeline[" + i + "]";
            BehaviorData.TimelineBin bin = bins.get(i);
            require(bin.startMs() == (long) i * width, path + ".startMs", "timeline gap");
            require(bin.endMs() == Math.min((long) (i + 1) * width, duration), path + ".endMs", "timeline boundary mismatch");
            for (String key : KEYS) {
                BehaviorData.Pair pair = bin.metrics().get(key);
                require(pair.denominatorMs() <= bin.endMs() - bin.startMs(), path + ".metrics." + key,
                        "bin duration overflow");
                totals.get(key)[0] += pair.numeratorMs();
                totals.get(key)[1] += pair.denominatorMs();
            }
        }
        for (String key : KEYS) {
            BehaviorData.Metric metric = data.metrics().get(key);
            long[] total = totals.get(key);
            require(total[0] == metric.numeratorMs() && total[1] == metric.denominatorMs(), "$.metrics." + key,
                    "timeline sum mismatch");
        }
    }

    private static void events(BehaviorData data) {
        List<BehaviorData.Event> events = data.events();
        Map<String, Integer> ends = new HashMap<>();
        for (int i = 0; i < events.size(); i++) {
            String path = "$.events[" + i + "]";
            BehaviorData.Event event = events.get(i);
            if (i > 0) {
                require(order(events.get(i - 1), event) <= 0, path, "event order mismatch");
            }
            require(EVENT_METRICS.get(event.type()).equals(event.sourceMetric()), path + ".sourceMetric",
                    "event metric mismatch");
            require(AVAILABLE.equals(data.metrics().get(event.sourceMetric()).status()), path,
                    "event for unavailable metric");
            require(0 <= event.startMs() && event.startMs() < event.endMs() && event.endMs() <= data.durationMs(),
                    path, "invalid event range");
            require(event.endMs() - event.startMs() >= MIN_EVENT_MS, path, "event too short");
            require(ends.getOrDefault(event.type(), 0) <= event.startMs(), path, "overlapping event");
            ends.put(event.type(), event.endMs());
        }
        // 한 구간 안에서 같은 종류 이벤트가 차지한 시간은 그 지표가 조건 밖이었던 시간(분모 - 분자)을 넘을 수 없다.
        for (int i = 0; i < data.timeline().size(); i++) {
            BehaviorData.TimelineBin bin = data.timeline().get(i);
            for (Map.Entry<String, String> kind : EVENT_METRICS.entrySet()) {
                long marked = 0;
                for (BehaviorData.Event event : events) {
                    if (event.type().equals(kind.getKey())) {
                        marked += Math.max(0, Math.min(event.endMs(), bin.endMs()) - Math.max(event.startMs(), bin.startMs()));
                    }
                }
                BehaviorData.Pair pair = bin.metrics().get(kind.getValue());
                require(marked <= pair.denominatorMs() - pair.numeratorMs(), "$.timeline[" + i + "]",
                        "event contradicts timeline");
            }
        }
    }

    /** 이벤트 정렬 기준: 시작 → 종류 → 끝. */
    private static int order(BehaviorData.Event a, BehaviorData.Event b) {
        int byStart = Integer.compare(a.startMs(), b.startMs());
        if (byStart != 0) {
            return byStart;
        }
        int byType = a.type().compareTo(b.type());
        return byType != 0 ? byType : Integer.compare(a.endMs(), b.endMs());
    }

    private static void warnings(BehaviorData data) {
        List<String> warnings = data.warnings();
        require(warnings.equals(new ArrayList<>(new TreeSet<>(warnings))), "$.warnings", "warning order or duplicates");
        require(warnings.contains("EVENTS_TRUNCATED") == data.eventsTruncated(), "$.eventsTruncated",
                "truncation mismatch");
        // 앞 60개만 남겼다는 표시다. 60개보다 적으면 잘린 것이 아니다.
        require(!data.eventsTruncated() || data.events().size() == MAX_EVENTS, "$.eventsTruncated",
                "truncated events must be full");
        double duration = data.durationMs();
        require(warnings.contains("LOW_SAMPLE_COVERAGE") == (data.sampledDurationMs() / duration < READY_SAMPLE_COVERAGE),
                "$.warnings", "sampling warning");
        require(warnings.contains("LOW_FACE_COVERAGE") == low(data, HEAD, SMILE), "$.warnings", "coverage warning mismatch");
        require(warnings.contains("LOW_POSE_COVERAGE") == low(data, SHOULDER, UPPER_BODY), "$.warnings",
                "coverage warning mismatch");
    }

    private static boolean low(BehaviorData data, String... keys) {
        for (String key : keys) {
            if (data.metrics().get(key).denominatorMs() / (double) data.durationMs() < READY_COVERAGE) {
                return true;
            }
        }
        return false;
    }
}
