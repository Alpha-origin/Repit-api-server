package repit.repit_api_server.domain.company.importer;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.annotation.Transactional;
import repit.repit_api_server.domain.company.entity.CompanyEntity;
import repit.repit_api_server.domain.company.entity.CompanyFactEntity;
import repit.repit_api_server.domain.company.entity.CompanySourceEntity;
import repit.repit_api_server.domain.company.entity.enums.CompanyCategory;
import repit.repit_api_server.domain.company.entity.enums.CompanyStatus;
import repit.repit_api_server.domain.company.importer.CompanyDataImporter.Result;
import repit.repit_api_server.domain.company.repository.CompanyFactRepository;
import repit.repit_api_server.domain.company.repository.CompanyRepository;
import repit.repit_api_server.domain.company.repository.CompanySourceRepository;
import repit.repit_api_server.domain.company.service.CompanyQueryService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기업 데이터 가져오기를 실제 스키마에 대고 확인한다(04 §3-4).
 *
 * <p>파일을 고치고 지우는 경우를 보려고 실제로 배포되는 샘플 패키지(company-data/sample)를 임시 폴더에 복사해 그 위치를 넘긴다.
 * 복사본을 따로 두지 않는다 — 두면 샘플을 고쳐도 이 테스트는 옛 복사본으로 통과한다.
 * 테스트 트랜잭션은 끝나면 롤백되고, 기업별 트랜잭션은 그 안에 합류한다.
 */
@SpringBootTest
@Transactional
class CompanyDataImporterTest {

    private static final List<String> SAMPLES = List.of("sample-commerce.json", "sample-games.json", "sample-pay.json");

    @Autowired
    private CompanyDataImporter importer;
    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private CompanyFactRepository companyFactRepository;
    @Autowired
    private CompanySourceRepository companySourceRepository;
    @Autowired
    private CompanyQueryService companyQueryService;

    @PersistenceContext
    private EntityManager entityManager;

    @TempDir
    private Path dir;

    @BeforeEach
    void setUp() throws IOException {
        // 로컬 DB에는 기동할 때 가져온 기업이 있을 수 있다. 테스트 트랜잭션 안에서 비우고, 끝나면 롤백된다.
        entityManager.createNativeQuery("update interview set company_id = null where company_id is not null")
                .executeUpdate();
        entityManager.createNativeQuery("delete from company_fact").executeUpdate();
        entityManager.createNativeQuery("delete from company_source").executeUpdate();
        entityManager.createNativeQuery("update company set parent_company_id = null").executeUpdate();
        entityManager.createNativeQuery("delete from company").executeUpdate();

        for (String fileName : SAMPLES) {
            copy(fileName, dir.resolve(fileName));
        }
    }

    private static void copy(String sample, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try (InputStream in = new ClassPathResource("company-data/sample/" + sample).getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 샘플 파일 한 군데를 바꿔 쓴다. 바꿀 문자열이 없으면 테스트가 엉뚱한 것을 확인하므로 바로 실패시킨다. */
    private void rewrite(String fileName, String from, String to) throws IOException {
        Path file = dir.resolve(fileName);
        String content = Files.readString(file);
        assertThat(content).contains(from);
        Files.writeString(file, content.replace(from, to));
    }

    private String location() {
        return "file:" + dir.toAbsolutePath() + "/*.json";
    }

    private CompanyEntity company(String code) {
        return companyRepository.findByCode(code).orElseThrow();
    }

    private List<Long> factIds(String code) {
        return companyFactRepository.findAllByCompanyIdOrderBySortOrder(company(code).getCompanyId()).stream()
                .map(CompanyFactEntity::getFactId)
                .toList();
    }

    @Test
    void 샘플_세_건을_새로_반영한다() throws IOException {
        assertThat(importer.importFrom(location())).isEqualTo(new Result(3, 0, 0, 0, 0));

        // jsonb·날짜 컬럼까지 실제로 들어갔는지 DB에서 다시 읽는다.
        entityManager.flush();
        entityManager.clear();

        CompanyEntity commerce = company("sample-commerce");
        assertThat(commerce.getStatus()).isEqualTo(CompanyStatus.PUBLISHED);
        assertThat(commerce.getDisplayName()).isEqualTo("샘플커머스");
        assertThat(commerce.getCategory()).isEqualTo(CompanyCategory.CONSUMER);
        assertThat(commerce.getAliases()).containsExactly("샘플마켓", "Sample Commerce");
        assertThat(commerce.getLegalSize()).isNull();
        assertThat(commerce.getContentHash()).hasSize(64);

        List<CompanySourceEntity> sources = companySourceRepository.findAll().stream()
                .filter(source -> source.getCompanyId().equals(commerce.getCompanyId()))
                .toList();
        assertThat(sources).singleElement().extracting(CompanySourceEntity::getSourceKey).isEqualTo("about");

        List<CompanyFactEntity> facts = companyFactRepository.findAllByCompanyIdOrderBySortOrder(commerce.getCompanyId());
        assertThat(facts).extracting(CompanyFactEntity::getTitle)
                .containsExactly("샘플마켓", "샘플배송", "재고 초과 판매", "배송 상태 지연", "오너십", "고객 관점");
        assertThat(facts).extracting(CompanyFactEntity::getSortOrder).containsExactly(0, 1, 2, 3, 4, 5);
        assertThat(facts).allMatch(fact -> fact.getSourceId().equals(sources.getFirst().getSourceId()));
        assertThat(facts.get(4).getPositiveSignals())
                .containsExactly("맡은 문제를 스스로 정의하고 끝까지 해결한 경험을 구체적으로 설명함");
        assertThat(facts.getFirst().getPositiveSignals()).isEmpty();
    }

    @Test
    void 같은_파일로_다시_가져오면_모두_그대로다() throws IOException {
        importer.importFrom(location());

        assertThat(importer.importFrom(location())).isEqualTo(new Result(0, 0, 3, 0, 0));
    }

    @Test
    void 내용이_바뀐_기업만_갱신하고_fact를_다시_넣는다() throws IOException {
        importer.importFrom(location());
        List<Long> gamesBefore = factIds("sample-games");
        List<Long> payBefore = factIds("sample-pay");

        rewrite("sample-games.json", "모바일 게임\"", "모바일 대전 게임\"");

        assertThat(importer.importFrom(location())).isEqualTo(new Result(0, 1, 2, 0, 0));
        assertThat(factIds("sample-games")).hasSameSizeAs(gamesBefore).doesNotContainAnyElementsOf(gamesBefore);
        assertThat(factIds("sample-pay")).isEqualTo(payBefore);
        assertThat(companyFactRepository.findAllByCompanyIdOrderBySortOrder(company("sample-games").getCompanyId())
                .getFirst().getContent()).isEqualTo("여러 이용자가 실시간으로 대전하는 모바일 대전 게임");
    }

    @Test
    void 파일이_빠진_기업은_숨기고_돌아오면_다시_공개한다() throws IOException {
        importer.importFrom(location());
        Files.delete(dir.resolve("sample-games.json"));

        assertThat(importer.importFrom(location())).isEqualTo(new Result(0, 0, 2, 1, 0));
        assertThat(company("sample-games").getStatus()).isEqualTo(CompanyStatus.HIDDEN);

        copy("sample-games.json", dir.resolve("sample-games.json"));
        assertThat(importer.importFrom(location())).isEqualTo(new Result(0, 1, 2, 0, 0));
        assertThat(company("sample-games").getStatus()).isEqualTo(CompanyStatus.PUBLISHED);
    }

    @Test
    void 검증에_실패한_파일은_건너뛰고_기존_상태를_둔다() throws IOException {
        importer.importFrom(location());
        String payHash = company("sample-pay").getContentHash();

        // 규칙 7 위반(금지 표현)과 깨진 JSON.
        rewrite("sample-pay.json", "\"interviewerBrief\": \"샘플페이는", "\"interviewerBrief\": \"합격을 돕는 샘플페이는");
        Files.writeString(dir.resolve("sample-games.json"), "{ 깨진 JSON");

        assertThat(importer.importFrom(location())).isEqualTo(new Result(0, 0, 1, 0, 2));
        assertThat(company("sample-pay").getStatus()).isEqualTo(CompanyStatus.PUBLISHED);
        assertThat(company("sample-pay").getContentHash()).isEqualTo(payHash);
        assertThat(company("sample-games").getStatus()).isEqualTo(CompanyStatus.PUBLISHED);
    }

    @Test
    void code가_겹치는_두_파일은_둘_다_건너뛴다() throws IOException {
        Files.delete(dir.resolve("sample-pay.json"));
        copy("sample-pay.json", dir.resolve("a/sample-pay.json"));
        copy("sample-pay.json", dir.resolve("b/sample-pay.json"));

        assertThat(importer.importFrom("file:" + dir.toAbsolutePath() + "/**/*.json"))
                .isEqualTo(new Result(2, 0, 0, 0, 2));
        assertThat(companyRepository.findByCode("sample-pay")).isEmpty();
    }

    @Test
    void 위치를_비우거나_파일이_없으면_아무것도_하지_않는다() throws IOException {
        importer.importFrom(location());
        Path empty = Files.createDirectory(dir.resolve("empty"));

        assertThat(importer.importFrom("")).isEqualTo(Result.NONE);
        assertThat(importer.importFrom(null)).isEqualTo(Result.NONE);
        // 위치를 잘못 적었을 수 있다. 숨기지도 않는다.
        assertThat(importer.importFrom("file:" + empty.toAbsolutePath() + "/*.json")).isEqualTo(Result.NONE);
        assertThat(companyRepository.findAllByStatus(CompanyStatus.PUBLISHED)).hasSize(3);
    }

    private CompanyEntity parentOf(String code) {
        entityManager.flush();
        entityManager.clear();
        CompanyEntity parent = company(code).getParent();
        return parent == null ? null : company(parent.getCode());
    }

    /** 모회사 파일이 잠깐 틀렸다고 자회사 연결까지 끊지 않는다. 모회사가 묶음에서 아예 빠지면 그때 끊는다. */
    @Test
    void 모회사_파일이_실패하면_연결을_두고_빠지면_끊는다() throws IOException {
        rewrite("sample-commerce.json", "\"parentCode\": null", "\"parentCode\": \"sample-pay\"");
        importer.importFrom(location());
        assertThat(parentOf("sample-commerce").getCode()).isEqualTo("sample-pay");
        // 목록은 모회사를 함께 읽어 이름을 싣는다(findWithParentByStatus).
        assertThat(companyQueryService.search("샘플커머스", null).getFirst().parentDisplayName()).isEqualTo("샘플페이");

        rewrite("sample-pay.json", "\"interviewerBrief\": \"샘플페이는", "\"interviewerBrief\": \"합격을 돕는 샘플페이는");
        importer.importFrom(location());
        assertThat(parentOf("sample-commerce").getCode()).isEqualTo("sample-pay");

        Files.delete(dir.resolve("sample-pay.json"));
        importer.importFrom(location());
        assertThat(parentOf("sample-commerce")).isNull();
    }
}
