package repit.repit_api_server.domain.userdata.feedback.service;

import org.junit.jupiter.api.Test;
import repit.repit_api_server.domain.company.dto.CompanySnapshot;
import repit.repit_api_server.domain.userdata.feedback.dto.response.CompanyFitResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 기업 관점 코멘트의 근거 정책(00 §6). 면접 스냅샷과 그 작업의 답변 있는 문항에 맞는 항목만 남는다. */
class CompanyFitReaderTest {

    private static final CompanySnapshot SNAPSHOT = new CompanySnapshot("12", "샘플커머스", "CONSUMER",
            List.of(new CompanySnapshot.Fact("101", "BUSINESS", "샘플마켓", "온라인 거래 서비스", List.of(), List.of()),
                    new CompanySnapshot.Fact("102", "TALENT", "고객 관점", "고객 불편을 먼저 본다",
                            List.of("사용자 불편을 근거로 우선순위를 정함"), List.of()),
                    new CompanySnapshot.Fact("103", "TALENT", "오너십", "끝까지 책임진다", List.of(), List.of()),
                    new CompanySnapshot.Fact("104", "PROBLEM", "재고 초과 판매", "동시 주문 재고 문제", List.of(), List.of())),
            "샘플커머스는 가상의 기업이다.");
    // 문항 id는 불투명한 문자열이다. 숫자가 아니어도 된다.
    private static final Set<String> ANSWERED = Set.of("q1", "q2", "7");

    private static CompanyFitResponse read(String json) {
        return CompanyFitReader.read(JsonMapper.shared().readTree(json), SNAPSHOT, ANSWERED, 1L);
    }

    private static String item(String factId, String detailField, String detail, String questionIds) {
        return """
                { "factId": "%s", "kind": "MISSION_VISION", "focus": "모델이 지은 이름", "%s": "%s", "questionIds": %s }"""
                .formatted(factId, detailField, detail, questionIds);
    }

    private static String fit(String comment, String matched, String gaps) {
        return """
                { "comment": "%s", "matchedFocus": [%s], "gaps": [%s] }""".formatted(comment, matched, gaps);
    }

    @Test
    void 맞는_항목은_kind와_focus를_스냅샷에서_채운다() {
        CompanyFitResponse fit = read(fit("  고객 관점이 잘 드러났습니다.  ",
                item("102", "evidence", "사용자 문의를 근거로 우선순위를 정했다고 설명했습니다.", "[\"q2\"]"),
                item("103", "suggestion", "결정 뒤 결과를 확인한 과정을 덧붙이면 좋습니다.", "[\"q1\"]")));

        assertThat(fit.comment()).isEqualTo("고객 관점이 잘 드러났습니다.");
        assertThat(fit.matchedFocus()).singleElement().satisfies(item -> {
            assertThat(item.factId()).isEqualTo("102");
            assertThat(item.kind()).isEqualTo("TALENT");
            assertThat(item.focus()).isEqualTo("고객 관점");
            assertThat(item.questionIds()).containsExactly("q2");
        });
        assertThat(fit.gaps()).singleElement().satisfies(gap -> {
            assertThat(gap.kind()).isEqualTo("TALENT");
            assertThat(gap.focus()).isEqualTo("오너십");
            assertThat(gap.suggestion()).startsWith("결정 뒤");
        });
    }

    @Test
    void 스냅샷에_없는_factId는_뺀다() {
        CompanyFitResponse fit = read(fit("코멘트",
                item("999", "evidence", "근거", "[\"q1\"]") + "," + item("101", "evidence", "근거", "[\"q1\"]"), ""));

        assertThat(fit.matchedFocus()).extracting(CompanyFitResponse.Item::factId).containsExactly("101");
    }

    @Test
    void 답변_있는_문항만_남기고_겹친_id를_뺀다() {
        CompanyFitResponse fit = read(fit("코멘트",
                item("101", "evidence", "근거", "[\"q9\", \"q1\", \"q1\", 7, \"7\"]"), ""));

        // 정수로 와도 같은 글자로 받는다. 순서는 받은 대로다.
        assertThat(fit.matchedFocus().getFirst().questionIds()).containsExactly("q1", "7");
    }

    @Test
    void 유효_문항이_없는_항목은_뺀다() {
        CompanyFitResponse fit = read(fit("코멘트",
                item("101", "evidence", "근거", "[\"q9\"]") + ","
                        + item("102", "evidence", "근거", "[]") + ","
                        + "{ \"factId\": \"103\", \"evidence\": \"근거\" }" + ","
                        + item("104", "evidence", "근거", "[\"q2\"]"), ""));

        assertThat(fit.matchedFocus()).extracting(CompanyFitResponse.Item::factId).containsExactly("104");
    }

    @Test
    void 근거_문장이_비었거나_150자를_넘으면_항목을_뺀다() {
        CompanyFitResponse fit = read(fit("코멘트",
                item("101", "evidence", "  ", "[\"q1\"]") + ","
                        + item("102", "evidence", "가".repeat(151), "[\"q1\"]") + ","
                        + item("103", "evidence", "가".repeat(150), "[\"q1\"]"),
                item("104", "suggestion", "", "[\"q1\"]")));

        assertThat(fit.matchedFocus()).extracting(CompanyFitResponse.Item::factId).containsExactly("103");
        assertThat(fit.gaps()).isEmpty();
    }

    @Test
    void 같은_목록의_같은_fact는_첫_유효_항목만_두고_목록끼리는_겹쳐도_된다() {
        CompanyFitResponse fit = read(fit("코멘트",
                item("102", "evidence", "근거 없음", "[\"q9\"]") + ","
                        + item("102", "evidence", "첫 유효", "[\"q1\"]") + ","
                        + item("102", "evidence", "두 번째", "[\"q2\"]"),
                item("102", "suggestion", "보완", "[\"q2\"]")));

        assertThat(fit.matchedFocus()).singleElement().extracting(CompanyFitResponse.Item::evidence)
                .isEqualTo("첫 유효");
        assertThat(fit.gaps()).singleElement().extracting(CompanyFitResponse.Gap::factId).isEqualTo("102");
    }

    @Test
    void 목록마다_세_개까지_둔다() {
        CompanyFitResponse fit = read(fit("코멘트",
                item("101", "evidence", "근거", "[\"q1\"]") + ","
                        + item("102", "evidence", "근거", "[\"q1\"]") + ","
                        + item("103", "evidence", "근거", "[\"q1\"]") + ","
                        + item("104", "evidence", "근거", "[\"q1\"]"), ""));

        assertThat(fit.matchedFocus()).extracting(CompanyFitResponse.Item::factId).containsExactly("101", "102", "103");
    }

    @Test
    void 항목이_있었는데_모두_빠지면_전체를_비운다() {
        assertThat(read(fit("코멘트",
                item("999", "evidence", "근거", "[\"q1\"]"),
                item("101", "suggestion", "보완", "[\"q9\"]")))).isNull();
    }

    @Test
    void 처음부터_두_목록이_비었으면_총평만_둔다() {
        CompanyFitResponse fit = read(fit("이번 답변에서는 관찰할 기회가 없었습니다.", "", ""));

        assertThat(fit.comment()).isEqualTo("이번 답변에서는 관찰할 기회가 없었습니다.");
        assertThat(fit.matchedFocus()).isEmpty();
        assertThat(fit.gaps()).isEmpty();
        assertThat(read("{ \"comment\": \"총평\" }").comment()).isEqualTo("총평");
    }

    @Test
    void 총평이_비었거나_300자를_넘으면_자르지_않고_비운다() {
        String item = item("101", "evidence", "근거", "[\"q1\"]");

        assertThat(read(fit("  ", item, ""))).isNull();
        assertThat(read(fit("가".repeat(301), item, ""))).isNull();
        // 이모지 하나는 한 글자다(분석 서버 Python len과 같다).
        assertThat(read(fit("😀".repeat(300), item, "")).comment()).hasSize(600);
    }

    @Test
    void 모양이_틀리면_비운다() {
        assertThat(read("[]")).isNull();
        assertThat(read("\"문자열\"")).isNull();
        assertThat(read("{ \"comment\": 3 }")).isNull();
        assertThat(read("{ \"comment\": \"코멘트\", \"gaps\": {} }")).isNull();
        // 항목 하나가 객체가 아니면 그 항목만 뺀다.
        assertThat(read("{ \"comment\": \"코멘트\", \"matchedFocus\": [1, " + item("101", "evidence", "근거", "[\"q1\"]")
                + "] }").matchedFocus()).hasSize(1);
    }

    @Test
    void 기업을_고르지_않았거나_값이_없으면_비운다() {
        JsonNode valid = JsonMapper.shared().readTree(fit("코멘트", "", ""));

        assertThat(CompanyFitReader.read(valid, null, ANSWERED, 1L)).isNull();
        assertThat(CompanyFitReader.read(null, SNAPSHOT, ANSWERED, 1L)).isNull();
        assertThat(CompanyFitReader.read(JsonMapper.shared().readTree("null"), SNAPSHOT, ANSWERED, 1L)).isNull();
    }
}
