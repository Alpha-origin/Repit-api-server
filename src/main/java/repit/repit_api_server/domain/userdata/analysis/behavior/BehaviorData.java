package repit.repit_api_server.domain.userdata.analysis.behavior;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * 영상 행동 관찰 결과 v1(behavior-v1). 분석 서버가 {@code result.analysis.data}에 싣는 문서를 {@link BehaviorValidator}가
 * 타입·의미까지 검증한 뒤에만 만든다. 웹에는 이 값을 그대로 내려준다.
 *
 * <p>지표는 연습용 관찰치다. 면접 점수·신뢰성·합격 가능성에 더하지 않고, 이 서버가 다시 계산하거나 고치지 않는다.
 * 필드 모양은 분석 서버 {@code docs/behavior-data.schema.json}과 같다. 측정할 수 없는 지표의 {@code value}는 0이 아니라
 * null이다 — 유효한 0%와 구분해야 한다.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record BehaviorData(
        String schemaVersion,
        String modelVersion,
        String thresholdsVersion,
        String videoId,
        int durationMs,
        int sampleIntervalMs,
        int sampledFrameCount,
        int sampledDurationMs,
        Map<String, Metric> metrics,
        List<TimelineBin> timeline,
        List<Event> events,
        boolean eventsTruncated,
        List<String> summary,
        List<String> warnings) {

    /** 지표 하나. value = 분자/분모, coverageRatio = 분모/전체 길이. 둘 다 소수점 6자리 반올림. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Metric(String status, Double value, int numeratorMs, int denominatorMs, double coverageRatio,
                         String unavailableReason) {
    }

    public record Pair(int numeratorMs, int denominatorMs) {
    }

    public record TimelineBin(int startMs, int endMs, Map<String, Pair> metrics) {
    }

    public record Event(String type, String sourceMetric, int startMs, int endMs) {
    }
}
