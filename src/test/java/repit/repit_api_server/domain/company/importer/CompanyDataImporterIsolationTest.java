package repit.repit_api_server.domain.company.importer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import repit.repit_api_server.domain.company.entity.CompanyEntity;
import repit.repit_api_server.domain.company.entity.enums.CompanyStatus;
import repit.repit_api_server.domain.company.importer.CompanyDataImporter.Result;
import repit.repit_api_server.domain.company.repository.CompanyRepository;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 가져오기를 실제 트랜잭션 경계 그대로 돌린다(04 §3-4 "기업 하나 = 트랜잭션 하나").
 *
 * <p>테스트 트랜잭션으로 감싸면 가져오기가 그 트랜잭션에 합류해, 한 기업의 DB 오류가 트랜잭션 전체를 망가뜨린다. 그래서
 * 감싸지 않는다. 가져오기는 이번 파일에 없는 공개 기업을 숨기므로, 시작할 때 공개였던 기업은 끝나고 다시 공개로 돌리고
 * 이 테스트가 만든 기업은 지운다.
 */
@SpringBootTest
class CompanyDataImporterIsolationTest {

    private static final JsonMapper MAPPER = JsonMapper.shared();

    @Autowired
    private CompanyDataImporter importer;
    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @TempDir
    private Path dir;

    // 다른 데이터와 겹치지 않는 code 접두어와 DART 고유번호.
    private final String prefix = "iso-" + UUID.randomUUID().toString().substring(0, 8);
    private final String dartCorpCode = String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    private List<Long> publishedBefore;

    @BeforeEach
    void rememberPublished() {
        publishedBefore = companyRepository.findAllByStatus(CompanyStatus.PUBLISHED).stream()
                .map(CompanyEntity::getCompanyId)
                .toList();
    }

    @AfterEach
    void cleanUp() {
        String like = prefix + "-%";
        jdbcTemplate.update("delete from company_fact where company_id in (select company_id from company where code like ?)", like);
        jdbcTemplate.update("delete from company_source where company_id in (select company_id from company where code like ?)", like);
        jdbcTemplate.update("update company set parent_company_id = null where code like ?", like);
        jdbcTemplate.update("delete from company where code like ?", like);
        publishedBefore.forEach(id -> jdbcTemplate.update("update company set status = 'PUBLISHED' where company_id = ?", id));
    }

    private String code(String name) {
        return prefix + "-" + name;
    }

    /** 샘플을 바탕으로 이 테스트의 기업 패키지를 쓴다. 파일 이름은 code와 같아야 한다. */
    private void writePackage(String name, String dart, Consumer<ObjectNode> change) throws IOException {
        ObjectNode node;
        try (InputStream in = new ClassPathResource("company-data/sample/sample-commerce.json").getInputStream()) {
            node = (ObjectNode) MAPPER.readTree(in);
        }
        node.put("code", code(name));
        node.put("displayName", "격리 " + name);
        node.putArray("aliases");
        if (dart == null) {
            node.putNull("dartCorpCode");
        } else {
            node.put("dartCorpCode", dart);
        }
        change.accept(node);
        Files.writeString(dir.resolve(code(name) + ".json"), MAPPER.writeValueAsString(node));
    }

    private void writePackage(String name, String dart) throws IOException {
        writePackage(name, dart, node -> { });
    }

    private Result importAll() throws IOException {
        return importer.importFrom("file:" + dir.toAbsolutePath() + "/*.json");
    }

    private CompanyEntity company(String name) {
        return companyRepository.findByCode(code(name)).orElseThrow();
    }

    /** 이 묶음 안에서 DART 고유번호를 b에서 a로 옮겼다. a가 먼저 처리돼 처음엔 막혀도, b를 반영한 뒤 다시 해 들어간다. */
    @Test
    void 묶음_안에서_옮긴_식별자는_처리_순서와_상관없이_반영한다() throws IOException {
        writePackage("a", null);
        writePackage("b", dartCorpCode);
        assertThat(importAll().created()).isEqualTo(2);

        writePackage("a", dartCorpCode);
        writePackage("b", null);
        Result result = importAll();

        assertThat(result.updated()).isEqualTo(2);
        assertThat(result.skipped()).isZero();
        assertThat(company("a").getDartCorpCode()).isEqualTo(dartCorpCode);
        assertThat(company("b").getDartCorpCode()).isNull();
    }

    /** 같은 법인을 다른 code로 다시 냈다. 숨긴 옛 기업이 식별자를 놓아 새 기업이 들어간다. */
    @Test
    void 숨긴_기업이_들고_있던_식별자는_새_기업이_받는다() throws IOException {
        writePackage("old", dartCorpCode);
        importAll();

        Files.delete(dir.resolve(code("old") + ".json"));
        writePackage("new", dartCorpCode);
        Result result = importAll();

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.hidden()).isEqualTo(1);
        assertThat(result.skipped()).isZero();
        assertThat(company("new").getDartCorpCode()).isEqualTo(dartCorpCode);
        assertThat(company("old").getStatus()).isEqualTo(CompanyStatus.HIDDEN);
        assertThat(company("old").getDartCorpCode()).isNull();
    }

    /**
     * 한 기업이 저장 단계에서 실패해도 그 기업만 롤백하고 나머지는 반영한다. b는 공개 기업 x가 아직 들고 있는 DART 고유번호를
     * 요구해 두 번 다 UNIQUE에 걸린다(x의 파일은 이번에 검증에 실패해 x는 그대로 공개로 남는다).
     */
    @Test
    void 한_기업이_DB에서_실패해도_그_기업만_건너뛰고_나머지는_반영한다() throws IOException {
        writePackage("x", dartCorpCode);
        importAll();

        writePackage("x", dartCorpCode, node -> node.put("interviewerBrief", "합격 비결을 알려주는 기업"));
        writePackage("a", null);
        writePackage("b", dartCorpCode);
        writePackage("c", null);
        Result result = importAll();

        assertThat(result.created()).isEqualTo(2);
        assertThat(result.skipped()).isEqualTo(2);
        assertThat(company("a").getStatus()).isEqualTo(CompanyStatus.PUBLISHED);
        assertThat(company("c").getStatus()).isEqualTo(CompanyStatus.PUBLISHED);
        assertThat(companyRepository.findByCode(code("b"))).isEmpty();
        assertThat(company("x").getStatus()).isEqualTo(CompanyStatus.PUBLISHED);
        assertThat(company("x").getDartCorpCode()).isEqualTo(dartCorpCode);
    }
}
