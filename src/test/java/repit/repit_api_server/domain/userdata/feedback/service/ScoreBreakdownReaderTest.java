package repit.repit_api_server.domain.userdata.feedback.service;

import org.junit.jupiter.api.Test;
import repit.repit_api_server.domain.userdata.feedback.dto.response.AxisScoreResponse;
import repit.repit_api_server.domain.userdata.feedback.dto.response.CredibilityResponse;
import repit.repit_api_server.domain.userdata.feedback.dto.response.MotivationSummaryResponse;
import repit.repit_api_server.domain.userdata.feedback.dto.response.ResponseSummaryResponse;
import repit.repit_api_server.domain.userdata.feedback.dto.response.ScoreBreakdownResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
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

    /** axis-v4 본문에는 판정·근거 문항 수·응답 적중·지원 동기·신뢰성이 없다. 지금과 똑같이 읽히고 새 칸은 빈다. */
    @Test
    void axis_v4_본문은_새_필드가_비어_있다() {
        ScoreBreakdownResponse breakdown = read("""
                {
                  "scoringVersion": "axis-v4",
                  "axes": [
                    { "axis": "INTENT", "score": 88, "weight": 35 },
                    { "axis": "DEPTH", "score": 38, "weight": 25 },
                    { "axis": "SPECIFICITY", "score": 63, "weight": 25 },
                    { "axis": "ACCURACY", "score": null, "weight": null }
                  ],
                  "consistencyScore": 75
                }
                """, 63);

        assertThat(breakdown.getAxes()).extracting(AxisScoreResponse::getVerdict, AxisScoreResponse::getEvidenceCount)
                .containsOnly(tuple(null, null));
        assertThat(breakdown.getResponse()).isNull();
        assertThat(breakdown.getMotivation()).isNull();
        assertThat(breakdown.getCredibility()).isNull();
    }

    /** 연동 계약(01 문서) 4.2 예시. 종합 점수만 축 점수와 가중치로 다시 계산한 값(66)으로 둔다. */
    @Test
    void competency_v1_산출_근거를_읽는다() {
        ScoreBreakdownResponse breakdown = read("""
                {
                  "scoringVersion": "competency-v1",
                  "axes": [
                    { "axis": "PROBLEM_SOLVING", "score": 75, "weight": 30, "verdict": "SOLID", "evidenceCount": 3 },
                    { "axis": "KNOWLEDGE", "score": 58, "weight": 25, "verdict": "BORDERLINE", "evidenceCount": 2 },
                    { "axis": "OWNERSHIP", "score": 70, "weight": 25, "verdict": "SOLID", "evidenceCount": 3 },
                    { "axis": "COLLABORATION", "score": null, "weight": null, "verdict": null, "evidenceCount": 0 },
                    { "axis": "GROWTH", "score": 50, "weight": 10, "verdict": "BORDERLINE", "evidenceCount": 1 }
                  ],
                  "consistencyScore": 75,
                  "response": { "score": 88, "hitCount": 5, "gradedCount": 6 },
                  "motivation": { "score": 50, "verdict": "BORDERLINE", "evidenceCount": 1 },
                  "credibility": {
                    "score": 75,
                    "issues": [
                      { "kind": "ROLE", "questionIds": ["101", "105"],
                        "quotes": ["결제 모듈은 제가 전부 맡았습니다", "결제 쪽은 동료가 개발했고"] }
                    ]
                  }
                }
                """, 66);

        assertThat(breakdown.getScoringVersion()).isEqualTo("competency-v1");
        assertThat(breakdown.getAxes())
                .extracting(AxisScoreResponse::getAxis, AxisScoreResponse::getScore, AxisScoreResponse::getWeight,
                        AxisScoreResponse::getVerdict, AxisScoreResponse::getEvidenceCount)
                .containsExactly(
                        tuple("PROBLEM_SOLVING", 75, 30, "SOLID", 3),
                        tuple("KNOWLEDGE", 58, 25, "BORDERLINE", 2),
                        tuple("OWNERSHIP", 70, 25, "SOLID", 3),
                        tuple("COLLABORATION", null, null, null, 0),
                        tuple("GROWTH", 50, 10, "BORDERLINE", 1));
        assertThat(breakdown.getResponse()).isEqualTo(new ResponseSummaryResponse(88, 5, 6));
        assertThat(breakdown.getMotivation()).isEqualTo(new MotivationSummaryResponse(50, "BORDERLINE", 1));
        assertThat(breakdown.getCredibility()).isEqualTo(new CredibilityResponse(75, List.of(
                new CredibilityResponse.Issue("ROLE", List.of("101", "105"),
                        List.of("결제 모듈은 제가 전부 맡았습니다", "결제 쪽은 동료가 개발했고")))));
    }

    /** 판정 오타나 음수 개수가 와도 그 칸만 비운다. 축 점수와 가중치는 그대로 쓸 수 있다. */
    @Test
    void 쓸_수_없는_판정과_근거_문항_수는_비운다() {
        ScoreBreakdownResponse breakdown = read("""
                { "axes": [
                    { "axis": "PROBLEM_SOLVING", "score": 75, "weight": 30, "verdict": "Solid", "evidenceCount": -1 },
                    { "axis": "KNOWLEDGE", "score": 58, "weight": 25, "verdict": 3, "evidenceCount": 1.5 },
                    { "axis": "OWNERSHIP", "score": 70, "weight": 25, "verdict": "STRONG", "evidenceCount": "3" },
                    { "axis": "GROWTH", "score": 50, "weight": 10, "verdict": "WEAK", "evidenceCount": 2.0 }
                ] }
                """, 66);

        assertThat(breakdown.getAxes())
                .extracting(AxisScoreResponse::getScore, AxisScoreResponse::getVerdict, AxisScoreResponse::getEvidenceCount)
                .containsExactly(
                        tuple(75, null, null),
                        tuple(58, null, null),
                        tuple(70, "STRONG", null),
                        tuple(50, "WEAK", 2));
    }

    @Test
    void 응답_적중과_지원_동기는_모양이_어긋난_칸만_비운다() {
        ScoreBreakdownResponse breakdown = read("""
                {
                  "axes": [],
                  "response": { "score": 140, "hitCount": -2, "gradedCount": "6" },
                  "motivation": { "score": null, "verdict": "GOOD", "evidenceCount": 0 },
                  "credibility": "없음"
                }
                """, 0);

        assertThat(breakdown.getResponse()).isEqualTo(new ResponseSummaryResponse(100, null, null));
        assertThat(breakdown.getMotivation()).isEqualTo(new MotivationSummaryResponse(null, null, 0));
        assertThat(breakdown.getCredibility()).isNull();

        assertThat(read("{ \"response\": [88, 5, 6], \"motivation\": 50 }", null))
                .satisfies(notObjects -> {
                    assertThat(notObjects.getResponse()).isNull();
                    assertThat(notObjects.getMotivation()).isNull();
                });
    }

    /** 문항 id와 인용이 둘씩 짝이 맞아야 화면이 "Q1 ↔ Q5"로 그린다. 짝이 안 맞는 항목만 버린다. */
    @Test
    void 짝이_맞지_않는_모순_항목은_버리고_모르는_종류는_OTHER로_둔다() {
        String longQuote = "가".repeat(1_001);
        ScoreBreakdownResponse breakdown = read("""
                {
                  "credibility": {
                    "score": 50,
                    "issues": [
                      { "kind": "ROLE", "questionIds": ["101"], "quotes": ["가", "나"] },
                      { "kind": "ROLE", "questionIds": ["101", "102", "103"], "quotes": ["가", "나"] },
                      { "kind": "ROLE", "questionIds": [101, 102], "quotes": ["가", "나"] },
                      { "kind": "CAUSE", "questionIds": ["101", "102"], "quotes": ["가", " "] },
                      { "kind": "CAUSE", "questionIds": ["101", "102"], "quotes": ["가", "%s"] },
                      { "kind": "CAUSE", "questionIds": ["101", "102"], "quotes": "가, 나" },
                      "ROLE",
                      { "kind": "DOCUMENT", "questionIds": ["101", "102"], "quotes": ["가", "나"] },
                      { "questionIds": ["103", "104"], "quotes": ["다", "라"] },
                      { "kind": "DETAIL", "questionIds": ["105", "106"], "quotes": ["마", "바"] }
                    ]
                  }
                }
                """.formatted(longQuote), null);

        assertThat(breakdown.getCredibility().score()).isEqualTo(50);
        assertThat(breakdown.getCredibility().issues()).containsExactly(
                new CredibilityResponse.Issue("OTHER", List.of("101", "102"), List.of("가", "나")),
                new CredibilityResponse.Issue("OTHER", List.of("103", "104"), List.of("다", "라")),
                new CredibilityResponse.Issue("DETAIL", List.of("105", "106"), List.of("마", "바")));
    }

    @Test
    void 모순_목록이_없거나_배열이_아니면_빈_목록이다() {
        assertThat(read("{ \"credibility\": { \"score\": 75 } }", null).getCredibility())
                .isEqualTo(new CredibilityResponse(75, List.of()));
        assertThat(read("{ \"credibility\": { \"score\": null, \"issues\": \"ROLE\" } }", null).getCredibility())
                .isEqualTo(new CredibilityResponse(null, List.of()));
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
