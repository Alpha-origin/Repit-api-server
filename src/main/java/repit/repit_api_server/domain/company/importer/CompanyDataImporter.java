package repit.repit_api_server.domain.company.importer;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import repit.repit_api_server.domain.company.entity.CompanyEntity;
import repit.repit_api_server.domain.company.entity.CompanyFactEntity;
import repit.repit_api_server.domain.company.entity.CompanySourceEntity;
import repit.repit_api_server.domain.company.entity.enums.CompanyStatus;
import repit.repit_api_server.domain.company.repository.CompanyFactRepository;
import repit.repit_api_server.domain.company.repository.CompanyRepository;
import repit.repit_api_server.domain.company.repository.CompanySourceRepository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 기동할 때 기업 데이터 패키지(JSON)를 읽어 DB에 반영한다.
 *
 * <p>기동을 실패시키지 않는다. 데이터 문제로 API 전체가 뜨지 않으면 안 된다. 문제가 있는 파일만 건너뛰고
 * 오류 로그를 남긴다. 위치를 비워 두면 아무것도 하지 않고, 공개 기업은 기존 DB 그대로다.
 *
 * <p>API는 1대 전제다. 여러 대가 동시에 가져와도 code UNIQUE와 기업별 트랜잭션 때문에 데이터가 깨지지는 않는다.
 */
@Component
@RequiredArgsConstructor
public class CompanyDataImporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CompanyDataImporter.class);

    private final CompanyRepository companyRepository;
    private final CompanySourceRepository companySourceRepository;
    private final CompanyFactRepository companyFactRepository;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final ResourcePatternResolver resourceResolver = new PathMatchingResourcePatternResolver();

    // Spring 리소스 패턴. 예: classpath:company-data/sample/*.json
    @Value("${app.company.data-location:}")
    private String dataLocation;

    @Override
    public void run(ApplicationArguments args) {
        try {
            importFrom(dataLocation);
        } catch (Exception e) {
            log.error("기업 데이터를 가져오지 못했습니다. 기존 기업 정보로 기동합니다. location={}", dataLocation, e);
        }
    }

    public Result importFrom(String location) throws IOException {
        if (location == null || location.isBlank()) {
            log.info("기업 데이터 위치가 설정되지 않아 가져오지 않습니다");
            return Result.NONE;
        }
        Resource[] resources = resourceResolver.getResources(location);
        if (resources.length == 0) {
            // 위치를 잘못 적었을 수 있다. 여기서 숨김까지 하면 공개 기업이 모두 사라진다.
            log.warn("기업 데이터 파일이 없어 가져오지 않습니다. location={}", location);
            return Result.NONE;
        }
        Arrays.sort(resources, Comparator.comparing(Resource::getFilename));

        // 이번에 읽은 기업. 검증에 실패한 파일도 넣어, 그 기업은 숨기지 않고 기존 상태를 둔다.
        Set<String> readCodes = new HashSet<>();
        List<Loaded> loaded = new ArrayList<>();
        int skipped = 0;
        for (Resource resource : resources) {
            String fileName = resource.getFilename();
            readCodes.add(StringUtils.stripFilenameExtension(fileName));

            byte[] bytes;
            CompanyDataPackage pkg;
            try {
                bytes = resource.getContentAsByteArray();
                pkg = objectMapper.readValue(bytes, CompanyDataPackage.class);
            } catch (IOException | JacksonException e) {
                log.error("기업 데이터 파일을 읽지 못해 건너뜁니다. file={}, 오류={}", fileName, e.getMessage());
                skipped++;
                continue;
            }
            if (pkg == null) {
                log.error("기업 데이터 파일이 비어 있어 건너뜁니다. file={}", fileName);
                skipped++;
                continue;
            }
            if (pkg.code() != null) {
                readCodes.add(pkg.code());
            }

            List<String> errors = CompanyDataValidator.validate(fileName, pkg);
            if (!errors.isEmpty()) {
                log.error("기업 데이터 파일이 규칙에 맞지 않아 건너뜁니다. file={}, 오류={}", fileName, errors);
                skipped++;
                continue;
            }
            loaded.add(new Loaded(fileName, pkg, sha256(bytes)));
        }

        List<Loaded> unique = withoutDuplicates(loaded);
        skipped += loaded.size() - unique.size();

        int created = 0;
        int updated = 0;
        int unchanged = 0;
        List<CompanyDataPackage> applied = new ArrayList<>();
        for (Loaded each : unique) {
            try {
                switch (transactionTemplate.execute(status -> apply(each))) {
                    case CREATED -> created++;
                    case UPDATED -> updated++;
                    case UNCHANGED -> unchanged++;
                }
                applied.add(each.pkg());
            } catch (RuntimeException e) {
                log.error("기업 데이터를 반영하지 못해 건너뜁니다. file={}", each.fileName(), e);
                skipped++;
            }
        }

        transactionTemplate.executeWithoutResult(status -> linkParents(applied));
        int hidden = transactionTemplate.execute(status -> hideMissing(readCodes));

        Result result = new Result(created, updated, unchanged, hidden, skipped);
        log.info("기업 데이터 가져오기: 새로 {}, 갱신 {}, 그대로 {}, 숨김 {}, 건너뜀 {}",
                created, updated, unchanged, hidden, skipped);
        return result;
    }

    /** 묶음 규칙 8. code·DART 고유번호·사업자등록번호가 다른 파일과 겹치면 어느 쪽이 맞는지 몰라 둘 다 건너뛴다. */
    private List<Loaded> withoutDuplicates(List<Loaded> loaded) {
        Map<String, Long> counts = loaded.stream()
                .flatMap(each -> uniqueKeys(each.pkg()).stream())
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        List<Loaded> unique = new ArrayList<>();
        for (Loaded each : loaded) {
            List<String> duplicated = uniqueKeys(each.pkg()).stream()
                    .filter(key -> counts.get(key) > 1)
                    .toList();
            if (duplicated.isEmpty()) {
                unique.add(each);
            } else {
                log.error("다른 기업 데이터 파일과 겹쳐 건너뜁니다. file={}, 겹친 값={}", each.fileName(), duplicated);
            }
        }
        return unique;
    }

    private static List<String> uniqueKeys(CompanyDataPackage pkg) {
        List<String> keys = new ArrayList<>();
        keys.add("code=" + pkg.code());
        if (pkg.dartCorpCode() != null) {
            keys.add("dartCorpCode=" + pkg.dartCorpCode());
        }
        if (pkg.businessRegNo() != null) {
            keys.add("businessRegNo=" + pkg.businessRegNo());
        }
        return keys;
    }

    /**
     * 기업 하나를 반영한다. 내용이 그대로면 손대지 않고, 바뀌었으면 기업 컬럼을 고친 뒤 정보·출처를 지우고 다시 넣는다.
     *
     * <p>다시 넣으면 fact_id가 바뀐다. 이미 만든 면접은 스냅샷 안의 factId를 쓰므로 영향이 없다.
     */
    private Outcome apply(Loaded loaded) {
        CompanyDataPackage pkg = loaded.pkg();
        CompanyEntity company = companyRepository.findByCode(pkg.code()).orElse(null);
        if (company != null && loaded.hash().equals(company.getContentHash())
                && company.getStatus() == CompanyStatus.PUBLISHED) {
            return Outcome.UNCHANGED;
        }

        Outcome outcome;
        if (company == null) {
            company = CompanyEntity.builder().code(pkg.code()).build();
            outcome = Outcome.CREATED;
        } else {
            // fact가 출처를 가리키므로 fact부터 지운다.
            companyFactRepository.deleteAllByCompanyId(company.getCompanyId());
            companySourceRepository.deleteAllByCompanyId(company.getCompanyId());
            outcome = Outcome.UPDATED;
        }
        company.setDisplayName(pkg.displayName());
        company.setLegalName(pkg.legalName());
        company.setAliases(pkg.aliases());
        company.setCategory(pkg.category());
        company.setLegalSize(pkg.legalSize());
        company.setLegalSizeSource(pkg.legalSizeSource());
        company.setDartCorpCode(pkg.dartCorpCode());
        company.setBusinessRegNo(pkg.businessRegNo());
        company.setStatus(CompanyStatus.PUBLISHED);
        company.setInterviewerBrief(pkg.interviewerBrief());
        company.setDataAsOf(pkg.dataAsOf());
        company.setReviewedBy(pkg.review() == null ? null : pkg.review().reviewedBy());
        company.setReviewedAt(pkg.review() == null ? null : pkg.review().reviewedAt());
        company.setContentHash(loaded.hash());
        Long companyId = companyRepository.save(company).getCompanyId();

        Map<String, Long> sourceIds = new HashMap<>();
        for (CompanyDataPackage.Source source : pkg.sources()) {
            CompanySourceEntity saved = companySourceRepository.save(CompanySourceEntity.builder()
                    .companyId(companyId)
                    .sourceKey(source.sourceKey())
                    .sourceType(source.sourceType())
                    .url(source.url())
                    .title(source.title())
                    .scope(source.scope())
                    .publishedAt(source.publishedAt())
                    .collectedAt(source.collectedAt())
                    .build());
            sourceIds.put(source.sourceKey(), saved.getSourceId());
        }

        List<CompanyFactEntity> facts = new ArrayList<>();
        for (int order = 0; order < pkg.facts().size(); order++) {
            CompanyDataPackage.Fact fact = pkg.facts().get(order);
            facts.add(CompanyFactEntity.builder()
                    .companyId(companyId)
                    .kind(fact.kind())
                    .title(fact.title())
                    .content(fact.content())
                    .positiveSignals(fact.positiveSignals())
                    .concernSignals(fact.concernSignals())
                    .signalsOrigin(fact.signalsOrigin())
                    .sourceId(sourceIds.get(fact.sourceKey()))
                    .sortOrder(order)
                    .build());
        }
        companyFactRepository.saveAll(facts);
        return outcome;
    }

    /** 묶음 규칙 10. 모회사는 같은 묶음에서 반영된 기업이어야 한다. 없으면 연결만 빼고 가져온다. */
    private void linkParents(List<CompanyDataPackage> applied) {
        Set<String> codes = applied.stream().map(CompanyDataPackage::code).collect(Collectors.toSet());
        for (CompanyDataPackage pkg : applied) {
            String parentCode = pkg.parentCode();
            if (parentCode != null && !codes.contains(parentCode)) {
                log.warn("모회사가 이번 기업 데이터에 없어 연결하지 않습니다. code={}, parentCode={}",
                        pkg.code(), parentCode);
                parentCode = null;
            }
            CompanyEntity company = companyRepository.findByCode(pkg.code()).orElseThrow();
            company.setParent(parentCode == null ? null : companyRepository.findByCode(parentCode).orElseThrow());
        }
    }

    /** 이번에 읽은 파일에 아예 없는 공개 기업을 숨긴다. 행은 면접이 가리키고 있어 지우지 않는다. */
    private int hideMissing(Set<String> readCodes) {
        int hidden = 0;
        for (CompanyEntity company : companyRepository.findAllByStatus(CompanyStatus.PUBLISHED)) {
            if (!readCodes.contains(company.getCode())) {
                company.setStatus(CompanyStatus.HIDDEN);
                hidden++;
            }
        }
        return hidden;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Loaded(String fileName, CompanyDataPackage pkg, String hash) {
    }

    private enum Outcome { CREATED, UPDATED, UNCHANGED }

    public record Result(int created, int updated, int unchanged, int hidden, int skipped) {
        static final Result NONE = new Result(0, 0, 0, 0, 0);
    }
}
