package repit.repit_api_server.domain.userdata.feedback.service;

import org.junit.jupiter.api.Test;
import repit.repit_api_server.domain.userdata.feedback.dto.response.AxisScoreResponse;
import repit.repit_api_server.domain.userdata.feedback.dto.response.ScoreBreakdownResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * 계약이 확정되지 않은 산출 근거를 모양이 어긋나도 읽어내는지 본다.
 *
 * <p>여기서 읽지 못한 부분은 비울 뿐 콜백 전체를 거절하지 않는다. 거절하면 분석 서버가 채점 결과를 폐기한다.
 */
class ScoreBreakdownReaderTest {

    private static JsonNode json(String text) {
        return JsonMapper.shared().readTree(text);
    }

    private static ScoreBreakdownResponse read(String text, Integer total) {
        return ScoreBreakdownReader.read(json(text), total, "종합", 1L);
    }

    @Test
    void 계약대로_온_산출_근거를_읽는다() {
        ScoreBreakdownResponse breakdown = read("""
                {
                  "scoringVersion": "axis-v1",
                  "axes": [
                    { "axis": "INTENT", "score": 88, "weight": 35 },
                    { "axis": "DEPTH", "score": 38, "weight": 25 },
                    { "axis": "SPECIFICITY", "score": 63, "weight": 25 },
                    { "axis": "ACCURACY", "score": null, "weight": null }
                  ],
                  "consistencyScore": 75
                }
                """, 63);

        assertThat(breakdown.getScoringVersion()).isEqualTo("axis-v1");
        assertThat(breakdown.getConsistencyScore()).isEqualTo(75);
        assertThat(breakdown.getAxes())
                .extracting(AxisScoreResponse::getAxis, AxisScoreResponse::getScore, AxisScoreResponse::getWeight)
                .containsExactly(
                        tuple("INTENT", 88, 35),
                        tuple("DEPTH", 38, 25),
                        tuple("SPECIFICITY", 63, 25),
                        tuple("ACCURACY", null, null));
    }

    /** 정수로 받으면 0.35가 0으로 잘려 모든 축이 "× 0%"로 그려진다. */
    @Test
    void 비율로_온_가중치는_퍼센트로_바꾼다() {
        ScoreBreakdownResponse breakdown = read("""
                { "axes": [
                    { "axis": "INTENT", "score": 88, "weight": 0.35 },
                    { "axis": "DEPTH", "score": 38, "weight": 0.25 },
                    { "axis": "SPECIFICITY", "score": 63, "weight": 0.25 },
                    { "axis": "ACCURACY", "score": 100, "weight": 0.15 }
                ] }
                """, 71);

        assertThat(breakdown.getAxes()).extracting(AxisScoreResponse::getWeight)
                .containsExactly(35, 25, 25, 15);
    }

    /** 음수나 100을 넘는 가중치는 화면의 계산식을 망가뜨린다. 쓸 수 없는 값은 비운다. */
    @Test
    void 범위를_벗어난_가중치는_비운다() {
        ScoreBreakdownResponse breakdown = read("""
                { "axes": [
                    { "axis": "INTENT", "score": 88, "weight": -10 },
                    { "axis": "DEPTH", "score": 38, "weight": 150 },
                    { "axis": "SPECIFICITY", "score": 63, "weight": 25 }
                ] }
                """, 63);

        assertThat(breakdown.getAxes()).extracting(AxisScoreResponse::getWeight)
                .containsExactly(null, null, 25);
    }

    /** axis-v1은 LLM이 등급만 매긴다. 등급이 그대로 실려 와도 점수 자리만 비우고 나머지는 남긴다. */
    @Test
    void 숫자가_아닌_점수는_비우고_범위_밖_점수는_당긴다() {
        ScoreBreakdownResponse breakdown = read("""
                {
                  "axes": [
                    { "axis": "INTENT", "score": "A", "weight": 35 },
                    { "axis": "DEPTH", "score": "38", "weight": 25 },
                    { "axis": "SPECIFICITY", "score": 140, "weight": 25 },
                    { "axis": "ACCURACY", "score": 99.6, "weight": 15 }
                  ],
                  "consistencyScore": -5
                }
                """, 70);

        assertThat(breakdown.getAxes()).extracting(AxisScoreResponse::getScore)
                .containsExactly(null, 38, 100, 100);
        assertThat(breakdown.getConsistencyScore()).isZero();
    }

    /** 이름이 없는 축은 화면에 무슨 축인지 적을 수 없다. */
    @Test
    void 축_이름이_없는_항목은_버린다() {
        ScoreBreakdownResponse breakdown = read("""
                { "axes": [
                    { "score": 88, "weight": 35 },
                    { "axis": " ", "score": 38, "weight": 25 },
                    "INTENT",
                    { "axis": "DEPTH", "score": 38, "weight": 25 }
                ] }
                """, 38);

        assertThat(breakdown.getAxes()).extracting(AxisScoreResponse::getAxis).containsExactly("DEPTH");
    }

    @Test
    void 축_이름을_키로_한_축_목록도_읽는다() {
        ScoreBreakdownResponse breakdown = read("""
                { "axes": { "INTENT": { "score": 88, "weight": 35 }, "DEPTH": 38 } }
                """, 88);

        assertThat(breakdown.getAxes())
                .extracting(AxisScoreResponse::getAxis, AxisScoreResponse::getScore, AxisScoreResponse::getWeight)
                .containsExactly(tuple("INTENT", 88, 35), tuple("DEPTH", 38, null));
    }

    @Test
    void 객체가_아닌_산출_근거는_비운다() {
        assertThat(read("\"axis-v1\"", 70)).isNull();
        assertThat(read("[1, 2]", 70)).isNull();
        assertThat(ScoreBreakdownReader.read(null, 70, "종합", 1L)).isNull();
        assertThat(read("null", 70)).isNull();
    }

    @Test
    void 읽을_수_없는_축_목록은_비우고_나머지는_남긴다() {
        ScoreBreakdownResponse breakdown = read("""
                { "scoringVersion": "axis-v1", "axes": "INTENT", "consistencyScore": 75 }
                """, 70);

        assertThat(breakdown.getAxes()).isEmpty();
        assertThat(breakdown.getScoringVersion()).isEqualTo("axis-v1");
        assertThat(breakdown.getConsistencyScore()).isEqualTo(75);
    }

    @Test
    void 문항_축_점수는_맵으로도_리스트로도_읽는다() {
        Map<String, Integer> fromMap = ScoreBreakdownReader.readAxisScores(
                json("{ \"INTENT\": 88, \"DEPTH\": \"B\", \"ACCURACY\": null }"), "문항 1", 1L);
        assertThat(fromMap).containsOnlyKeys("INTENT", "DEPTH", "ACCURACY")
                .containsEntry("INTENT", 88)
                .containsEntry("DEPTH", null)
                .containsEntry("ACCURACY", null);

        Map<String, Integer> fromList = ScoreBreakdownReader.readAxisScores(
                json("[{ \"axis\": \"INTENT\", \"score\": 88 }, { \"score\": 10 }, { \"axis\": \"DEPTH\", \"score\": -3 }]"),
                "문항 1", 1L);
        assertThat(fromList).containsExactly(Map.entry("INTENT", 88), Map.entry("DEPTH", 0));

        assertThat(ScoreBreakdownReader.readAxisScores(json("88"), "문항 1", 1L)).isNull();
    }

    /** 컬럼보다 긴 버전을 그대로 넣으면 플러시에서 터져 채점 결과가 폐기된다. */
    @Test
    void 컬럼보다_긴_방식_버전은_잘라_넣는다() {
        String longVersion = "axis-v1+prompt-2026-09-28-hotfix-1+".repeat(3);
        ScoreBreakdownResponse breakdown = new ScoreBreakdownResponse(longVersion, null, null);

        assertThat(ScoreBreakdownReader.columnVersion(breakdown, 1L))
                .hasSize(ScoreBreakdownReader.SCORING_VERSION_MAX_LENGTH)
                .isEqualTo(longVersion.substring(0, ScoreBreakdownReader.SCORING_VERSION_MAX_LENGTH));
        assertThat(ScoreBreakdownReader.columnVersion(new ScoreBreakdownResponse("axis-v1", null, null), 1L))
                .isEqualTo("axis-v1");
        assertThat(ScoreBreakdownReader.columnVersion(null, 1L)).isNull();
    }
}
