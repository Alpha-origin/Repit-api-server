package repit.repit_api_server.domain.userdata.analysis.behavior;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 행동 관찰 계약(behavior-v1)과 영상 분석 종료 문서를 검증기가 계약대로 가르는지.
 *
 * <p>fixture는 Web PR #189(Alpha-origin/Repit-web codex/behavior-feedback@29148cb)의
 * {@code docs/behavior-analysis/contracts}에서 그대로 가져온 합성 데이터다. behavior-*.json은 분석 서버
 * {@code tests/behavior/fixtures}와 같다. 모델 정확도의 증거가 아니라 계약의 증거다.
 */
class BehaviorContractTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String VIDEO = "501";

    // ---- 정상 fixture ----

    @ParameterizedTest
    @CsvSource({"behavior-ready, ready", "behavior-partial, partial", "behavior-zero-face, partial"})
    void 공유_행동_데이터는_통과하고_품질_판정이_같다(String name, String quality) {
        BehaviorData data = BehaviorValidator.parse(fixture(name));

        assertThat(BehaviorValidator.quality(data)).isEqualTo(quality);
        // 내보내는 모양이 받은 문서와 같다. 웹에 그대로 내려간다.
        assertThat(JsonValues.same(JSON.convertValue(data, Object.class), fixture(name))).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"callback-ready, ready", "callback-partial, partial", "callback-zero-face, partial",
            "callback-unavailable, unavailable", "callback-unconfigured, unavailable", "callback-failed, failed"})
    void 공유_콜백은_통과한다(String name, String status) {
        VideoResultDocument.Accepted accepted = VideoResultDocument.validate(fixture(name), VIDEO);

        assertThat(accepted.status()).isEqualTo(status);
        assertThat(accepted.behavior() != null).isEqualTo(status.equals("ready") || status.equals("partial"));
    }

    @Test
    void 측정_불가_콜백의_오류는_공개_사전의_retryable로_남긴다() {
        VideoResultDocument.Accepted unavailable = VideoResultDocument.validate(fixture("callback-unavailable"), VIDEO);
        assertThat(unavailable.errorCode()).isEqualTo("BEHAVIOR_NOT_MEASURABLE");
        assertThat(unavailable.errorRetryable()).isFalse();

        VideoResultDocument.Accepted failed = VideoResultDocument.validate(fixture("callback-failed"), VIDEO);
        assertThat(failed.errorCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(failed.errorRetryable()).isFalse();
    }

    // ---- 행동 데이터 부정 변형 ----

    @ParameterizedTest
    @ValueSource(strings = {"value", "bin", "event", "nullValue", "extra", "status", "warning", "duration", "durationTrue",
            "durationText", "durationFraction", "nan", "truncatedText", "model", "schema", "reason", "eventSource",
            "eventShort", "eventOverlap", "eventOrder", "truncatedNotFull", "summaryLong", "unknownWarning",
            "duplicateWarning", "coverage", "sampleCount", "missingMetric", "timelineGap", "binOverflow", "emptySuccess",
            "intervalFraction", "faceExceeded"})
    void 어긋난_행동_데이터는_받지_않는다(String mutation) {
        Map<String, Object> raw = fixture("behavior-ready");
        mutateBehavior(raw, mutation);

        assertThatThrownBy(() -> BehaviorValidator.parse(raw)).isInstanceOf(VideoResultException.class);
    }

    @Test
    void 미측정_사유는_관찰_근거를_따른다() {
        Map<String, Object> zeroFace = fixture("behavior-zero-face");
        metric(zeroFace, "headFacingRatio").put("unavailableReason", "BLENDSHAPES_UNAVAILABLE");
        assertThatThrownBy(() -> BehaviorValidator.parse(zeroFace)).hasMessageContaining("reason mismatch");

        Map<String, Object> partial = fixture("behavior-partial");
        metric(partial, "shoulderLevelRatio").put("unavailableReason", "NO_SINGLE_FACE");
        assertThatThrownBy(() -> BehaviorValidator.parse(partial)).hasMessageContaining("reason mismatch");
    }

    /** 0%는 측정된 값이다. 측정 불가(null)와 섞이면 안 된다. */
    @Test
    void 얼굴_0퍼센트는_측정된_0이고_나머지는_null이다() {
        BehaviorData data = BehaviorValidator.parse(fixture("behavior-zero-face"));

        assertThat(data.metrics().get("faceVisibleRatio").value()).isEqualTo(0.0);
        assertThat(data.metrics().get("headFacingRatio").value()).isNull();
        assertThat(data.metrics().get("headFacingRatio").unavailableReason()).isEqualTo("NO_SINGLE_FACE");
    }

    @Test
    void 오류_메시지에는_받은_값을_싣지_않는다() {
        Map<String, Object> raw = fixture("behavior-ready");
        raw.put("videoId", "secret-signed-url?X-Amz-Signature=abc");

        assertThatCode(() -> BehaviorValidator.parse(raw)).doesNotThrowAnyException();
        raw.put("modelVersion", "secret-model");
        assertThatThrownBy(() -> BehaviorValidator.parse(raw))
                .isInstanceOf(VideoResultException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("secret"));
    }

    // ---- 콜백 포장 부정 변형 ----

    @ParameterizedTest
    @ValueSource(strings = {"readyWithoutData", "qualityMismatch", "retryableFlipped", "unknownCode", "retryableText",
            "errorExtraField", "dataVideoMismatch", "resultVideoMismatch", "durationMismatch", "durationMissing",
            "resultStatusMismatch", "analysisStatusMismatch", "failedWithResult", "failedWithoutError",
            "readyWithError", "readyWithFileError", "fileErrorMismatch", "unavailableWithData", "nul", "schemaVersion",
            "upperStatus", "missingAnalysisKey"})
    void 어긋난_종료_문서는_받지_않는다(String mutation) {
        Map<String, Object> raw = mutateCallback(mutation);

        assertThatThrownBy(() -> VideoResultDocument.validate(raw, VIDEO)).isInstanceOf(VideoResultException.class);
    }

    @Test
    void 포장에_계약에_없는_필드가_더_있으면_받는다() {
        Map<String, Object> raw = fixture("callback-ready");
        raw.put("traceId", "t-1");
        result(raw).put("extra", true);

        assertThatCode(() -> VideoResultDocument.validate(raw, VIDEO)).doesNotThrowAnyException();
    }

    // ---- 의미값 비교 ----

    @Test
    void 키_순서와_숫자_표기만_다른_문서는_같은_문서다() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("x", 1);
        a.put("y", List.of(1.0, "a"));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("y", List.of(1L, "a"));
        b.put("x", 1.0);

        assertThat(JsonValues.same(a, b)).isTrue();
        assertThat(JsonValues.same(a, Map.of("x", 1, "y", List.of("a", 1)))).isFalse();
        assertThat(JsonValues.same(Map.of("x", true), Map.of("x", "true"))).isFalse();
        assertThat(JsonValues.same(Map.of("x", 1), Map.of("x", 1, "z", 2))).isFalse();
    }

    // ---- 변형 ----

    private static void mutateBehavior(Map<String, Object> raw, String mutation) {
        switch (mutation) {
            case "value" -> metric(raw, "headFacingRatio").put("value", 0.99);
            case "bin" -> pair(raw, 0, "faceVisibleRatio").put("numeratorMs", 10_001);
            case "event" -> event(raw, 0).put("endMs", ((Number) event(raw, 0).get("endMs")).intValue() + 1000);
            case "nullValue" -> metric(raw, "faceVisibleRatio").put("value", null);
            case "extra" -> raw.put("image", "forbidden");
            case "status" -> metric(raw, "faceVisibleRatio").putAll(map("status", "UNAVAILABLE", "value", null,
                    "unavailableReason", "LOW_COVERAGE"));
            case "warning" -> raw.put("warnings", new ArrayList<>(List.of("LOW_POSE_COVERAGE")));
            case "duration" -> raw.put("durationMs", 4000);
            case "durationTrue" -> raw.put("durationMs", true);
            case "durationText" -> raw.put("durationMs", "60000");
            case "durationFraction" -> raw.put("durationMs", 60000.5);
            case "nan" -> metric(raw, "faceVisibleRatio").put("value", Double.NaN);
            case "truncatedText" -> raw.put("eventsTruncated", "false");
            case "model" -> raw.put("modelVersion", "mp-face-pose-v2");
            case "schema" -> raw.put("schemaVersion", "behavior-v2");
            case "reason" -> metric(raw, "faceVisibleRatio").put("unavailableReason", "LOW_COVERAGE");
            case "eventSource" -> event(raw, 0).put("sourceMetric", "smileShapeRatio");
            case "eventShort" -> event(raw, 0).put("endMs", ((Number) event(raw, 0).get("startMs")).intValue() + 1999);
            case "eventOverlap" -> {
                Map<String, Object> copy = new LinkedHashMap<>(event(raw, 0));
                events(raw).add(1, copy);
            }
            case "eventOrder" -> {
                List<Object> events = events(raw);
                if (events.size() < 2) {
                    Map<String, Object> later = new LinkedHashMap<>(event(raw, 0));
                    later.put("startMs", 40_000);
                    later.put("endMs", 42_000);
                    events.addFirst(later);
                } else {
                    events.add(events.removeFirst());
                }
            }
            case "truncatedNotFull" -> {
                raw.put("eventsTruncated", true);
                List<Object> warnings = new ArrayList<>(warnings(raw));
                warnings.add("EVENTS_TRUNCATED");
                warnings.sort(null);
                raw.put("warnings", warnings);
            }
            case "summaryLong" -> raw.put("summary", List.of("가".repeat(201)));
            case "unknownWarning" -> raw.put("warnings", List.of("ATTENTION_LOW"));
            case "duplicateWarning" -> raw.put("warnings", List.of("MULTIPLE_PEOPLE", "MULTIPLE_PEOPLE"));
            case "coverage" -> metric(raw, "smileShapeRatio").put("coverageRatio", 0.5);
            case "sampleCount" -> raw.put("sampledFrameCount", 301);
            case "missingMetric" -> ((Map<?, ?>) raw.get("metrics")).remove("smileShapeRatio");
            case "timelineGap" -> bin(raw, 1).put("startMs", 10_001);
            case "binOverflow" -> pair(raw, 0, "smileShapeRatio").put("denominatorMs", 10_200);
            case "emptySuccess" -> {
                for (String key : BehaviorPolicy.KEYS) {
                    metric(raw, key).putAll(map("status", "UNAVAILABLE", "value", null, "unavailableReason", "LOW_COVERAGE"));
                }
            }
            case "intervalFraction" -> raw.put("sampleIntervalMs", 200.0);
            case "faceExceeded" -> metric(raw, "faceVisibleRatio").put("numeratorMs", 1);
            default -> throw new IllegalArgumentException(mutation);
        }
    }

    private static Map<String, Object> mutateCallback(String mutation) {
        Map<String, Object> raw;
        switch (mutation) {
            case "readyWithoutData" -> {
                raw = fixture("callback-ready");
                analysis(raw).put("data", null);
            }
            case "qualityMismatch" -> {
                raw = fixture("callback-ready");
                setStatus(raw, "partial");
            }
            case "retryableFlipped" -> {
                raw = fixture("callback-unavailable");
                error(analysis(raw)).put("retryable", true);
            }
            case "unknownCode" -> {
                raw = fixture("callback-unavailable");
                error(analysis(raw)).put("code", "MODEL_CRASHED");
            }
            case "retryableText" -> {
                raw = fixture("callback-unavailable");
                error(analysis(raw)).put("retryable", "false");
            }
            case "errorExtraField" -> {
                raw = fixture("callback-failed");
                error(raw).put("stack", "trace");
            }
            case "dataVideoMismatch" -> {
                raw = fixture("callback-ready");
                data(raw).put("videoId", "502");
            }
            case "resultVideoMismatch" -> {
                raw = fixture("callback-ready");
                result(raw).put("videoId", "502");
                data(raw).put("videoId", "502");
            }
            case "durationMismatch" -> {
                raw = fixture("callback-ready");
                result(raw).put("durationMs", 60_001);
            }
            case "durationMissing" -> {
                raw = fixture("callback-partial");
                result(raw).put("durationMs", null);
            }
            case "resultStatusMismatch" -> {
                raw = fixture("callback-ready");
                result(raw).put("status", "partial");
            }
            case "analysisStatusMismatch" -> {
                raw = fixture("callback-partial");
                analysis(raw).put("status", "ready");
            }
            case "failedWithResult" -> {
                raw = fixture("callback-failed");
                raw.put("result", fixture("callback-ready").get("result"));
            }
            case "failedWithoutError" -> {
                raw = fixture("callback-failed");
                raw.put("error", null);
            }
            case "readyWithError" -> {
                raw = fixture("callback-ready");
                analysis(raw).put("error", error(analysis(fixture("callback-unavailable"))));
            }
            case "readyWithFileError" -> {
                raw = fixture("callback-ready");
                result(raw).put("error", error(analysis(fixture("callback-unavailable"))));
            }
            case "fileErrorMismatch" -> {
                raw = fixture("callback-unavailable");
                result(raw).put("error", map("code", "VIDEO_DECODE_FAILED", "message", "x", "retryable", false));
            }
            case "unavailableWithData" -> {
                raw = fixture("callback-unavailable");
                analysis(raw).put("data", fixture("behavior-ready"));
            }
            case "nul" -> {
                raw = fixture("callback-ready");
                data(raw).put("summary", List.of("a\u0000b"));
            }
            case "schemaVersion" -> {
                raw = fixture("callback-ready");
                raw.put("schemaVersion", 1);
            }
            case "upperStatus" -> {
                raw = fixture("callback-ready");
                setStatus(raw, "READY");
            }
            case "missingAnalysisKey" -> {
                raw = fixture("callback-unconfigured");
                analysis(raw).remove("data");
            }
            default -> throw new IllegalArgumentException(mutation);
        }
        return raw;
    }

    private static void setStatus(Map<String, Object> raw, String status) {
        raw.put("status", status);
        result(raw).put("status", status);
        analysis(raw).put("status", status);
    }

    // ---- 도구 ----

    static Map<String, Object> fixture(String name) {
        try (InputStream in = BehaviorContractTest.class.getResourceAsStream("/contracts/behavior/" + name + ".json")) {
            return JSON.readValue(in, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> events(Map<String, Object> raw) {
        return (List<Object>) raw.get("events");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> warnings(Map<String, Object> raw) {
        return (List<Object>) raw.get("warnings");
    }

    private static Map<String, Object> metric(Map<String, Object> raw, String key) {
        return obj(obj(raw.get("metrics")).get(key));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bin(Map<String, Object> raw, int index) {
        return obj(((List<Object>) raw.get("timeline")).get(index));
    }

    private static Map<String, Object> pair(Map<String, Object> raw, int bin, String key) {
        return obj(obj(bin(raw, bin).get("metrics")).get(key));
    }

    private static Map<String, Object> event(Map<String, Object> raw, int index) {
        return obj(events(raw).get(index));
    }

    private static Map<String, Object> result(Map<String, Object> callback) {
        return obj(callback.get("result"));
    }

    private static Map<String, Object> analysis(Map<String, Object> callback) {
        return obj(result(callback).get("analysis"));
    }

    private static Map<String, Object> data(Map<String, Object> callback) {
        return obj(analysis(callback).get("data"));
    }

    private static Map<String, Object> error(Map<String, Object> holder) {
        return obj(holder.get("error"));
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
