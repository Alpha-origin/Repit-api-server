package repit.repit_api_server.domain.company.importer;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/** 패키지 파일 하나에 적용하는 규칙(01 §3 1~7, 9). 샘플을 하나씩만 어겨 그 규칙이 걸리는지 본다. */
class CompanyDataValidatorTest {

    private static final JsonMapper MAPPER = JsonMapper.shared();

    private static ObjectNode read(String fileName) {
        try (InputStream in = new ClassPathResource("company-data/sample/" + fileName).getInputStream()) {
            return (ObjectNode) MAPPER.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> validate(String fileName, ObjectNode node) {
        return CompanyDataValidator.validate(fileName, MAPPER.treeToValue(node, CompanyDataPackage.class));
    }

    /** 샘플커머스를 한 군데만 바꿔 검사한다. */
    private static List<String> violate(Consumer<ObjectNode> change) {
        ObjectNode node = read("sample-commerce.json");
        change.accept(node);
        return validate("sample-commerce.json", node);
    }

    private static ArrayNode facts(ObjectNode node) {
        return (ArrayNode) node.get("facts");
    }

    private static ObjectNode fact(ObjectNode node, int index) {
        return (ObjectNode) facts(node).get(index);
    }

    private static void removeFacts(ObjectNode node, String kind) {
        ArrayNode facts = facts(node);
        for (int i = facts.size() - 1; i >= 0; i--) {
            if (kind.equals(facts.get(i).get("kind").stringValue())) {
                facts.remove(i);
            }
        }
    }

    @Test
    void 샘플_세_개는_오류가_없다() {
        for (String fileName : List.of("sample-commerce.json", "sample-pay.json", "sample-games.json")) {
            assertThat(validate(fileName, read(fileName))).as(fileName).isEmpty();
        }
    }

    // 규칙 1 — 형식

    @Test
    void schemaVersion이_1이_아니면_다른_규칙은_보지_않는다() {
        assertThat(violate(node -> node.put("schemaVersion", 2))).singleElement()
                .asString().contains("schemaVersion");
    }

    @Test
    void code_형식이_틀리면_오류다() {
        assertThat(violate(node -> node.put("code", "Sample_Commerce"))).anyMatch(e -> e.contains("code는"));
    }

    @Test
    void 필수_필드가_없으면_오류다() {
        assertThat(violate(node -> node.remove("interviewerBrief"))).anyMatch(e -> e.startsWith("interviewerBrief"));
        assertThat(violate(node -> node.putNull("dataAsOf"))).anyMatch(e -> e.startsWith("dataAsOf"));
    }

    @Test
    void 길이_상한을_넘으면_오류다() {
        assertThat(violate(node -> node.put("displayName", "가".repeat(31)))).anyMatch(e -> e.startsWith("displayName"));
        assertThat(violate(node -> fact(node, 0).put("title", "가".repeat(21)))).anyMatch(e -> e.startsWith("facts[0].title"));
        assertThat(violate(node -> fact(node, 0).put("content", "가".repeat(201))))
                .anyMatch(e -> e.startsWith("facts[0].content"));
        assertThat(violate(node -> node.put("interviewerBrief", "가".repeat(601))))
                .anyMatch(e -> e.startsWith("interviewerBrief"));
    }

    @Test
    void 개수_상한을_넘으면_오류다() {
        assertThat(violate(node -> {
            for (int i = 0; i < 15; i++) {
                facts(node).add(fact(node, 0).deepCopy());
            }
        })).anyMatch(e -> e.startsWith("facts는"));
        assertThat(violate(node -> ((ArrayNode) fact(node, 4).get("positiveSignals"))
                .add("하나").add("둘").add("셋"))).anyMatch(e -> e.startsWith("facts[4].positiveSignals는"));
    }

    @Test
    void 출처_주소가_https가_아니면_오류다() {
        assertThat(violate(node -> ((ObjectNode) node.get("sources").get(0)).put("url", "http://example.invalid/a")))
                .anyMatch(e -> e.startsWith("sources[0].url"));
    }

    // 규칙 2 — 파일 이름

    @Test
    void 파일_이름이_code와_다르면_오류다() {
        assertThat(validate("other.json", read("sample-commerce.json"))).anyMatch(e -> e.startsWith("파일 이름"));
    }

    // 규칙 3 — 공개 조건

    @Test
    void 인재상이나_주력_사업이_없으면_오류다() {
        assertThat(violate(node -> removeFacts(node, "TALENT"))).anyMatch(e -> e.startsWith("TALENT"));
        assertThat(violate(node -> removeFacts(node, "BUSINESS"))).anyMatch(e -> e.startsWith("BUSINESS"));
    }

    // 규칙 4 — 출처 연결

    @Test
    void fact가_없는_출처를_가리키면_오류다() {
        assertThat(violate(node -> fact(node, 0).put("sourceKey", "missing")))
                .anyMatch(e -> e.startsWith("facts[0].sourceKey가"));
    }

    @Test
    void 출처_키가_겹치면_오류다() {
        assertThat(violate(node -> ((ArrayNode) node.get("sources")).add(node.get("sources").get(0).deepCopy())))
                .anyMatch(e -> e.startsWith("sourceKey가 겹칩니다"));
    }

    // 규칙 5 — 신호

    @Test
    void 인재상이_아닌_fact에_신호가_있으면_오류다() {
        assertThat(violate(node -> fact(node, 0).putArray("positiveSignals").add("신호")))
                .anyMatch(e -> e.contains("TALENT가 아니라"));
    }

    @Test
    void 신호가_있는데_출처_구분이_없으면_오류다() {
        assertThat(violate(node -> fact(node, 4).putNull("signalsOrigin"))).anyMatch(e -> e.contains("signalsOrigin"));
    }

    // 규칙 6 — 규모

    @Test
    void 규모가_있는데_판정_근거가_없으면_오류다() {
        assertThat(violate(node -> node.put("legalSize", "LARGE"))).anyMatch(e -> e.startsWith("legalSize"));
    }

    // 규칙 7 — 금지 표현

    @Test
    void 금지_표현이_있으면_오류다() {
        assertThat(violate(node -> fact(node, 0).put("content", "저희가 운영하는 서비스")))
                .anyMatch(e -> e.contains("\"저희\""));
        assertThat(violate(node -> node.put("interviewerBrief", "공식 면접 정보")))
                .anyMatch(e -> e.contains("\"공식\""));
    }

    // 규칙 9 — 검수

    @Test
    void 실제_출처면_검수_기록이_있어야_한다() {
        Consumer<ObjectNode> real = node -> ((ObjectNode) node.get("sources").get(0)).put("sourceType", "CAREER_PAGE");

        assertThat(violate(real)).anyMatch(e -> e.contains("review"));
        assertThat(violate(real.andThen(node -> node.putObject("review")
                .put("reviewedBy", "검수자").put("reviewedAt", "2026-10-15")))).isEmpty();
    }
}
