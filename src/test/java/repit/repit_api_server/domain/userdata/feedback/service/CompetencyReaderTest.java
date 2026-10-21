package repit.repit_api_server.domain.userdata.feedback.service;

import org.junit.jupiter.api.Test;
import repit.repit_api_server.domain.userdata.feedback.dto.response.CompetencyScoreResponse;
import repit.repit_api_server.domain.userdata.feedback.dto.response.CompetencyScoreResponse.Evidence;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 문항의 응답 적중 등급과 역량별 근거(competency-v1)를 모양이 어긋나도 읽어내는지 본다.
 *
 * <p>어긋난 항목과 인용만 버리고 콜백 전체는 거절하지 않는다. 거절하면 분석 서버가 채점 결과를 폐기한다.
 */
class CompetencyReaderTest {

    private static JsonNode json(String text) {
        return JsonMapper.shared().readTree(text);
    }

    private static Integer level(String text) {
        return CompetencyReader.readLevel(json(text), "문항 101", 1L);
    }

    private static List<CompetencyScoreResponse> read(String text) {
        return CompetencyReader.read(json(text), "문항 101", 1L);
    }

    @Test
    void 응답_적중_등급은_0부터_4까지_정수만_받는다() {
        assertThat(level("0")).isZero();
        assertThat(level("4")).isEqualTo(4);
        assertThat(level("4.0")).isEqualTo(4);

        assertThat(level("5")).isNull();
        assertThat(level("-1")).isNull();
        assertThat(level("2.5")).isNull();
        assertThat(level("\"3\"")).isNull();
        assertThat(level("null")).isNull();
        assertThat(CompetencyReader.readLevel(null, "문항 101", 1L)).isNull();
    }

    /** 연동 계약(01 문서) 4.3 예시. */
    @Test
    void 계약대로_온_역량_목록을_읽는다() {
        List<CompetencyScoreResponse> competencies = read("""
                [
                  {
                    "competency": "PROBLEM_SOLVING", "role": "PRIMARY", "level": 3, "score": 75,
                    "evidence": [
                      { "slot": "reason", "quote": "재고 차감 경합이 잦아" },
                      { "slot": "alternatives", "quote": "DB 비관적 락은 커넥션을 오래 잡아서" }
                    ]
                  },
                  {
                    "competency": "KNOWLEDGE", "role": "SECONDARY", "level": 4, "score": 100,
                    "evidence": [ { "slot": "mechanism", "quote": "TTL이 지나면 락이 자동으로 풀리고" } ]
                  }
                ]
                """);

        assertThat(competencies).containsExactly(
                new CompetencyScoreResponse("PROBLEM_SOLVING", "PRIMARY", 3, 75, List.of(
                        new Evidence("reason", "재고 차감 경합이 잦아"),
                        new Evidence("alternatives", "DB 비관적 락은 커넥션을 오래 잡아서"))),
                new CompetencyScoreResponse("KNOWLEDGE", "SECONDARY", 4, 100, List.of(
                        new Evidence("mechanism", "TTL이 지나면 락이 자동으로 풀리고"))));
    }

    /** 지원 동기 문항은 종합에 들어가지 않는 MOTIVATION 하나로 온다. */
    @Test
    void 지원_동기도_역량_항목으로_읽는다() {
        assertThat(read("""
                [{ "competency": "MOTIVATION", "role": "PRIMARY", "level": 2, "score": 50, "evidence": [] }]
                """)).extracting(CompetencyScoreResponse::competency).containsExactly("MOTIVATION");
    }

    /** 무엇을 몇 점으로 매겼는지 모르는 항목은 화면에 그릴 수 없다. 그 항목만 버리고 나머지는 남긴다. */
    @Test
    void 코드나_등급이_어긋난_항목만_버린다() {
        List<CompetencyScoreResponse> competencies = read("""
                [
                  { "competency": "LEADERSHIP", "role": "PRIMARY", "level": 3, "score": 75 },
                  { "competency": "KNOWLEDGE", "role": "MAIN", "level": 3, "score": 75 },
                  { "competency": "KNOWLEDGE", "role": "PRIMARY", "level": 5, "score": 75 },
                  { "competency": "KNOWLEDGE", "role": "PRIMARY", "level": 3, "score": 101 },
                  { "competency": "KNOWLEDGE", "role": "PRIMARY", "level": 3 },
                  { "competency": "knowledge", "role": "PRIMARY", "level": 3, "score": 75 },
                  "OWNERSHIP",
                  { "competency": "OWNERSHIP", "role": "SECONDARY", "level": 2, "score": 50 }
                ]
                """);

        assertThat(competencies).containsExactly(
                new CompetencyScoreResponse("OWNERSHIP", "SECONDARY", 2, 50, List.of()));
    }

    /** 인용이 없어도 등급은 보여 줄 수 있다. 어긋난 인용만 버리고 항목은 남긴다. */
    @Test
    void 어긋난_근거_인용만_버린다() {
        String longQuote = "가".repeat(1_001);
        String longSlot = "s".repeat(41);
        List<CompetencyScoreResponse> competencies = read("""
                [{
                  "competency": "OWNERSHIP", "role": "PRIMARY", "level": 4, "score": 100,
                  "evidence": [
                    { "slot": "action", "quote": "배포 스크립트를 직접 고쳤고" },
                    { "slot": "method", "quote": " " },
                    { "slot": "", "quote": "롤백 절차를 문서로 남겼습니다" },
                    { "slot": "%s", "quote": "결과" },
                    { "slot": "result", "quote": "%s" },
                    { "slot": "result", "quote": 3 },
                    "배포 시간이 절반으로 줄었습니다",
                    { "slot": "result", "quote": "%s" }
                  ]
                }]
                """.formatted(longSlot, longQuote, "가".repeat(1_000)));

        assertThat(competencies).singleElement().satisfies(competency -> {
            assertThat(competency.level()).isEqualTo(4);
            assertThat(competency.evidence()).containsExactly(
                    new Evidence("action", "배포 스크립트를 직접 고쳤고"),
                    new Evidence("result", "가".repeat(1_000)));
        });
    }

    /** 분석 서버(Python)는 글자 수를 코드 포인트로 센다. 이모지가 섞인 인용도 같은 한도로 본다. */
    @Test
    void 인용_길이는_코드_포인트로_센다() {
        String emojiQuote = "😀".repeat(1_000);

        List<CompetencyScoreResponse> competencies = read("""
                [{ "competency": "GROWTH", "role": "PRIMARY", "level": 2, "score": 50,
                   "evidence": [{ "slot": "lesson", "quote": "%s" }] }]
                """.formatted(emojiQuote));

        assertThat(competencies.getFirst().evidence()).extracting(Evidence::quote).containsExactly(emojiQuote);
    }

    @Test
    void 근거_인용_목록이_배열이_아니면_비운_채_항목을_남긴다() {
        assertThat(read("""
                [{ "competency": "GROWTH", "role": "PRIMARY", "level": 1, "score": 25, "evidence": "많이 배웠다" }]
                """)).singleElement().satisfies(competency -> assertThat(competency.evidence()).isEmpty());
    }

    @Test
    void 배열이_아닌_역량_목록은_비운다() {
        assertThat(read("{ \"PROBLEM_SOLVING\": 75 }")).isNull();
        assertThat(read("\"PROBLEM_SOLVING\"")).isNull();
        assertThat(read("null")).isNull();
        assertThat(CompetencyReader.read(null, "문항 101", 1L)).isNull();
        assertThat(read("[]")).isEmpty();
    }
}
