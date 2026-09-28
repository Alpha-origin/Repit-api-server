package repit.repit_api_server.domain.userdata.feedback.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import repit.repit_api_server.domain.userdata.feedback.dto.response.AxisScoreResponse;
import repit.repit_api_server.domain.userdata.feedback.dto.response.ScoreBreakdownResponse;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 채점 콜백의 점수 산출 근거를 읽는다.
 *
 * <p>계약이 확정되지 않은 필드라 요청 본문에서는 모양을 가리지 않고 받아 여기서 푼다. 타입을 정해 받으면
 * 모양이 하나만 어긋나도(축 점수가 리스트로 오거나, 점수 대신 등급 문자열이 오거나) 본문 전체를 읽지 못해
 * 400이 나가고, 분석 서버는 두 번 더 시도한 뒤 채점 결과를 영구 폐기한다. 산출 근거는 없어도 화면이
 * 그려지는 부가 정보다. 읽지 못한 부분만 비우고 어긋난 사실을 로그로 남긴다.
 */
final class ScoreBreakdownReader {

    private static final Logger log = LoggerFactory.getLogger(ScoreBreakdownReader.class);

    // feedback.scoring_version 컬럼 길이. 넘는 값을 그대로 넣으면 플러시에서 터져 결과가 폐기된다.
    static final int SCORING_VERSION_MAX_LENGTH = 64;

    private ScoreBreakdownReader() {
    }

    /**
     * 종합 또는 면접관 점수의 산출 근거.
     *
     * <p>축 점수와 일관성 점수는 다른 점수처럼 0..100으로 당긴다. 가중치는 %로 맞추고, 쓸 수 없는
     * 가중치는 비운다. 축 점수와 가중치로 다시 계산한 값이 받은 점수와 어긋나면 화면의 "축 점수 × 가중치
     * = 최종" 계산이 틀려 보이지만, 어느 쪽이 맞는지는 여기서 알 수 없다. 받은 대로 두고 로그만 남긴다.
     *
     * @param total 이 근거로 설명할 점수(종합 점수 또는 면접관 점수). 이미 0..100으로 맞춘 값이다.
     */
    static ScoreBreakdownResponse read(JsonNode node, Integer total, String label, Long interviewId) {
        if (isAbsent(node)) {
            return null;
        }
        if (!node.isObject()) {
            log.warn("{} 점수 산출 근거가 객체가 아니라 비워 둡니다. interviewId={}, 받은 값={}",
                    label, interviewId, node);
            return null;
        }

        List<AxisScoreResponse> axes = readAxes(node.get("axes"), label, interviewId);
        warnIfTotalDiffers(axes, total, label, interviewId);

        return new ScoreBreakdownResponse(
                text(node.get("scoringVersion")),
                axes,
                score(node.get("consistencyScore"), label + " 일관성 점수", interviewId));
    }

    /**
     * 문항의 축별 점수. {@code {"INTENT": 88}} 모양을 기본으로 하되, 종합과 같은
     * {@code [{"axis": "INTENT", "score": 88}]} 모양으로 와도 읽는다. 해당 없는 축의 빈 값은 그대로 둔다.
     */
    static Map<String, Integer> readAxisScores(JsonNode node, String label, Long interviewId) {
        if (isAbsent(node)) {
            return null;
        }
        Map<String, Integer> scores = new LinkedHashMap<>();
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                scores.put(entry.getKey(),
                        score(entry.getValue(), label + " " + entry.getKey() + " 축 점수", interviewId));
            }
            return scores;
        }
        if (node.isArray()) {
            for (JsonNode element : node.values()) {
                String axis = element.isObject() ? text(element.get("axis")) : null;
                if (axis == null || axis.isBlank()) {
                    log.warn("{} 축 점수 중 축 이름이 없는 항목을 버립니다. interviewId={}, 받은 값={}",
                            label, interviewId, element);
                    continue;
                }
                scores.put(axis, score(element.get("score"), label + " " + axis + " 축 점수", interviewId));
            }
            return scores;
        }
        log.warn("{} 축 점수를 읽지 못해 비워 둡니다. interviewId={}, 받은 값={}", label, interviewId, node);
        return null;
    }

    /** 컬럼에 넣을 채점 방식 버전. 넘치면 잘라 넣는다. 원래 값은 산출 근거 jsonb에 그대로 남는다. */
    static String columnVersion(ScoreBreakdownResponse breakdown, Long interviewId) {
        String version = breakdown == null ? null : breakdown.getScoringVersion();
        if (version == null || version.length() <= SCORING_VERSION_MAX_LENGTH) {
            return version;
        }
        log.warn("채점 방식 버전이 {}자를 넘어 잘라 넣습니다. interviewId={}, 받은 값={}",
                SCORING_VERSION_MAX_LENGTH, interviewId, version);
        return version.substring(0, SCORING_VERSION_MAX_LENGTH);
    }

    /** 축 목록. 기본은 리스트이고, {@code {"INTENT": {"score": 88, "weight": 35}}}처럼 축 이름을 키로 와도 읽는다. */
    private static List<AxisScoreResponse> readAxes(JsonNode node, String label, Long interviewId) {
        if (isAbsent(node)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        List<JsonNode> values = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode element : node.values()) {
                String axis = element.isObject() ? text(element.get("axis")) : null;
                if (axis == null || axis.isBlank()) {
                    log.warn("{} 산출 근거에 축 이름이 없는 항목이 있어 버립니다. interviewId={}, 받은 값={}",
                            label, interviewId, element);
                    continue;
                }
                names.add(axis);
                values.add(element);
            }
        } else if (node.isObject()) {
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                names.add(entry.getKey());
                values.add(entry.getValue());
            }
        } else {
            log.warn("{} 산출 근거의 축 목록을 읽지 못해 비워 둡니다. interviewId={}, 받은 값={}",
                    label, interviewId, node);
            return List.of();
        }

        List<Integer> weights = weights(names, values, label, interviewId);
        List<AxisScoreResponse> axes = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            JsonNode value = values.get(i);
            // 축 이름을 키로 한 모양에서는 값이 점수 하나일 수도 있다.
            JsonNode scoreNode = value.isObject() ? value.get("score") : value;
            axes.add(new AxisScoreResponse(names.get(i),
                    score(scoreNode, label + " " + names.get(i) + " 축 점수", interviewId),
                    weights.get(i)));
        }
        return axes;
    }

    /**
     * 축마다 가중치(%).
     *
     * <p>계약은 정수 %다. 비율(0.35)로 오면 정수로 받는 순간 0으로 잘려 모든 축이 "× 0%"로 그려지므로, 쓸 수 있는
     * 가중치가 모두 1 이하이고 합도 1 이하이면 비율로 보고 %로 바꾼다. 음수나 100을 넘는 가중치는 계산식을
     * 망가뜨리므로 비운다.
     */
    private static List<Integer> weights(List<String> names, List<JsonNode> values, String label, Long interviewId) {
        List<Double> raw = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            JsonNode value = values.get(i);
            raw.add(value.isObject()
                    ? number(value.get("weight"), label + " " + names.get(i) + " 축 가중치", interviewId)
                    : null);
        }

        List<Double> usable = raw.stream().filter(w -> w != null && w > 0).toList();
        double sum = usable.stream().mapToDouble(Double::doubleValue).sum();
        boolean ratio = !usable.isEmpty() && usable.stream().allMatch(w -> w <= 1.0) && sum <= 1.0 + 1e-6;
        if (ratio) {
            log.warn("{} 가중치가 비율로 와서 %로 바꿉니다. interviewId={}, 받은 값={}", label, interviewId, raw);
        }

        List<Integer> weights = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            Double weight = raw.get(i);
            if (weight == null) {
                weights.add(null);
                continue;
            }
            double percent = ratio ? weight * 100 : weight;
            if (percent < 0 || percent > 100) {
                log.warn("{} {} 축 가중치가 0..100을 벗어나 비워 둡니다. interviewId={}, 받은 값={}",
                        label, names.get(i), interviewId, weight);
                weights.add(null);
                continue;
            }
            weights.add((int) Math.round(percent));
        }
        return weights;
    }

    private static void warnIfTotalDiffers(List<AxisScoreResponse> axes, Integer total, String label, Long interviewId) {
        long weighted = 0;
        long weightSum = 0;
        boolean scored = false;
        for (AxisScoreResponse axis : axes) {
            if (axis.getScore() == null) {
                continue;
            }
            scored = true;
            if (axis.getWeight() == null) {
                continue;
            }
            weighted += (long) axis.getScore() * axis.getWeight();
            weightSum += axis.getWeight();
        }
        if (weightSum <= 0) {
            if (scored) {
                log.warn("{} 산출 근거에 쓸 수 있는 가중치가 없어 점수를 되짚을 수 없습니다. interviewId={}, 축={}",
                        label, interviewId, axes.size());
            }
            return;
        }
        double recomputed = (double) weighted / weightSum;
        // 반올림 방식이 서버마다 달라도(.5에서 갈림) 같은 값으로 본다.
        if (total != null && Math.abs(recomputed - total) > 0.5) {
            log.warn("{} 점수가 축 점수와 가중치로 계산한 값과 다릅니다. interviewId={}, 받은 점수={}, 계산값={}",
                    label, interviewId, total, recomputed);
        }
    }

    /** 0..100 점수. 숫자가 아니면(등급 문자열 등) 비우고, 범위를 벗어나면 경계로 당긴다. */
    private static Integer score(JsonNode node, String what, Long interviewId) {
        Double value = number(node, what, interviewId);
        if (value == null) {
            return null;
        }
        int rounded = (int) Math.round(value);
        if (rounded >= 0 && rounded <= 100) {
            return rounded;
        }
        int clamped = Math.max(0, Math.min(100, rounded));
        log.warn("{}가 0..100을 벗어나 경계값으로 맞춥니다. interviewId={}, 받은 값={}, 저장={}",
                what, interviewId, value, clamped);
        return clamped;
    }

    /** 숫자로 읽을 수 있는 값. 숫자로 된 문자열("88")도 받는다. */
    private static Double number(JsonNode node, String what, Long interviewId) {
        if (isAbsent(node)) {
            return null;
        }
        if (node.isNumber()) {
            return finite(node.doubleValue(), node, what, interviewId);
        }
        if (node.isString()) {
            try {
                return finite(Double.parseDouble(node.stringValue().trim()), node, what, interviewId);
            } catch (NumberFormatException e) {
                // 아래에서 경고를 남긴다.
            }
        }
        log.warn("{}가 숫자가 아니라 비워 둡니다. interviewId={}, 받은 값={}", what, interviewId, node);
        return null;
    }

    private static Double finite(double value, JsonNode node, String what, Long interviewId) {
        if (Double.isFinite(value)) {
            return value;
        }
        log.warn("{}가 숫자가 아니라 비워 둡니다. interviewId={}, 받은 값={}", what, interviewId, node);
        return null;
    }

    private static String text(JsonNode node) {
        if (isAbsent(node) || node.isObject() || node.isArray()) {
            return null;
        }
        return node.isString() ? node.stringValue() : node.asString();
    }

    private static boolean isAbsent(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode();
    }
}
