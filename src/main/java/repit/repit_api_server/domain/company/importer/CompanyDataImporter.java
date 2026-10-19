package repit.repit_api_server.domain.company.importer;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import repit.repit_api_server.domain.company.entity.CompanyEntity;
import repit.repit_api_server.domain.company.entity.CompanyFactEntity;
import repit.repit_api_server.domain.company.entity.CompanySourceEntity;
import repit.repit_api_server.domain.company.entity.enums.CompanySourceType;
import repit.repit_api_server.domain.company.entity.enums.CompanyStatus;
import repit.repit_api_server.domain.company.repository.CompanyFactRepository;
import repit.repit_api_server.domain.company.repository.CompanyRepository;
import repit.repit_api_server.domain.company.repository.CompanySourceRepository;
import repit.repit_api_server.domain.company.service.CompanyVisibility;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 기동할 때 기업 데이터 패키지(JSON)를 읽어 DB에 반영한다.
 *
 * <p>기동을 실패시키지 않는다. 데이터 문제로 API 전체가 뜨지 않으면 안 된다. 문제가 있는 파일만 건너뛰고
 * 오류 로그를 남긴다. 위치를 비워 두면 가져오지 않고(설정한 숨김만 한다), 공개 기업은 기존 DB 그대로다.
 *
 * <p>숨김은 설정(app.company.hidden-codes)으로만 한다. 파일이 디렉터리에서 빠졌다고 숨기거나 지우지 않는다 — 위치를 잘못
 * 적거나 일부 파일만 배포돼도 공개 기업이 한꺼번에 사라지지 않는다. 빠진 기업은 경고만 남긴다.
 *
 * <p>SAMPLE 패키지(출처가 모두 SAMPLE)는 app.company.allow-sample이 켜진 환경(개발·스테이징)에서만 가져온다.
 *
 * <p>기존 기업은 행을 FOR UPDATE로 잡고 고친다. 면접 생성의 스냅샷은 같은 행을 FOR SHARE로 읽으므로 반쯤 바뀐 기업을
 * 스냅샷으로 남기지 않는다({@code CompanySnapshotFactory}).
 *
 * <p>로그에는 파일명·code·결과·사유만 남긴다. 파일 원문, 출처 주소, 신호 문장은 남기지 않는다.
 *
 * <p>API는 1대 전제다. 여러 대가 동시에 가져와도 code UNIQUE와 기업별 트랜잭션 때문에 데이터가 깨지지는 않는다.
 *
 * <p>웹 서버가 요청을 받기 전에 끝낸다. ApplicationRunner는 내장 서버가 뜬 뒤에 돌아, 그 사이 들어온 요청이 반쯤 반영된
 * 기업 목록을 보거나 갱신 중인 기업으로 면접을 만든다. 싱글턴이 모두 만들어진 뒤(마이그레이션·JPA 준비 완료)이고
 * 서버가 뜨기 전인 이 자리에서 돈다.
 */
@Component
@RequiredArgsConstructor
public class CompanyDataImporter implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(CompanyDataImporter.class);

    private final CompanyRepository companyRepository;
    private final CompanySourceRepository companySourceRepository;
    private final CompanyFactRepository companyFactRepository;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final CompanyVisibility companyVisibility;
    private final ResourcePatternResolver resourceResolver = new PathMatchingResourcePatternResolver();

    // Spring 리소스 패턴. 예: classpath:company-data/sample/*.json
    @Value("${app.company.data-location:}")
    private String dataLocation;

    // 내릴 기업의 code. 쉼표로 나눈다. 목록에서 빼면 다음 가져오기 때 다시 공개한다.
    @Value("${app.company.hidden-codes:}")
    private String hiddenCodes;

    @Override
    public void afterSingletonsInstantiated() {
        try {
            importFrom(dataLocation);
        } catch (Exception e) {
            log.error("기업 데이터를 가져오지 못했습니다. 기존 기업 정보로 기동합니다. location={}", dataLocation, e);
        }
    }

    public Result importFrom(String location) throws IOException {
        return importFrom(location, parseCodes(hiddenCodes));
    }

    public Result importFrom(String location, Set<String> hiddenCodes) throws IOException {
        // 숨김은 파일과 상관없이 설정만 보고 한다. 위치를 비워 둔 환경에서도 내릴 기업은 내린다.
        // 반영보다 먼저 한다 — 숨긴 기업이 들고 있던 식별자를 이번 묶음이 받을 수 있게 놓게 한 뒤 반영해야, code를 바꿔 다시 낸
        // 기업이 DART 고유번호 UNIQUE에 걸려 끝내 들어가지 못하는 일이 없다.
        int hidden = transactionTemplate.execute(status -> hide(hiddenCodes));
        if (location == null || location.isBlank()) {
            log.info("기업 데이터 위치가 설정되지 않아 가져오지 않습니다");
            return new Result(0, 0, 0, hidden, 0);
        }
        Resource[] resources = resourceResolver.getResources(location);
        if (resources.length == 0) {
            // 위치를 잘못 적었을 수 있다. 공개 기업은 그대로 둔다.
            log.warn("기업 데이터 파일이 없어 가져오지 않습니다. location={}", location);
            return new Result(0, 0, 0, hidden, 0);
        }
        Arrays.sort(resources, Comparator.comparing(Resource::getFilename));

        // 이번에 읽은 기업. 검증에 실패한 파일도 넣는다 — 모회사 파일이 실패했을 때 자회사의 기존 연결을 두는 데 쓴다.
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
                // 파서 메시지는 파일 내용 일부를 담는다. 예외 종류만 남긴다.
                log.error("기업 데이터 파일을 읽지 못해 건너뜁니다. file={}, 오류={}", fileName, e.getClass().getSimpleName());
                skipped++;
                continue;
            }
            if (pkg == null) {
                log.error("기업 데이터 파일이 비어 있어 건너뜁니다. file={}", fileName);
                skipped++;
                continue;
            }
            // 검증과 저장이 같은 값을 보도록 앞뒤 공백을 먼저 뺀다. 내용이 같은지는 파일 바이트의 해시로 본다.
            pkg = pkg.normalized();
            if (pkg.code() != null) {
                readCodes.add(pkg.code());
            }

            List<String> errors = CompanyDataValidator.validate(fileName, pkg);
            if (!errors.isEmpty()) {
                log.error("기업 데이터 파일이 규칙에 맞지 않아 건너뜁니다. file={}, code={}, 오류={}", fileName, pkg.code(), errors);
                skipped++;
                continue;
            }
            if (isSample(pkg) && !companyVisibility.allowsSample()) {
                // 운영에 샘플이 공개되지 않게 한다. 검수 예외를 운영 검수 우회로 쓰지 않는다.
                log.error("SAMPLE 기업 데이터는 이 환경에서 가져오지 않습니다. file={}, code={}", fileName, pkg.code());
                skipped++;
                continue;
            }
            if (hiddenCodes.contains(pkg.code())) {
                log.info("숨기도록 설정한 기업이라 반영하지 않습니다. file={}, code={}", fileName, pkg.code());
                continue;
            }
            loaded.add(new Loaded(fileName, pkg, sha256(bytes)));
        }

        List<Loaded> unique = withoutDuplicates(loaded);
        skipped += loaded.size() - unique.size();

        warnMissing(readCodes, hiddenCodes);
        transactionTemplate.executeWithoutResult(status -> releaseClaimedIdentifiers(unique));

        Map<Outcome, Integer> tally = new EnumMap<>(Outcome.class);
        List<CompanyDataPackage> applied = new ArrayList<>();
        List<Loaded> retry = new ArrayList<>();
        for (Loaded each : unique) {
            Outcome outcome = applyInOwnTransaction(each, true);
            if (outcome == null) {
                retry.add(each);
            } else {
                record(tally, applied, each, outcome);
            }
        }
        // 다른 기업이 먼저 놓아야 받을 수 있는 값(이 묶음 안에서 DART 고유번호를 옮긴 경우)은 처리 순서에 따라 처음에 막힌다.
        // 나머지를 반영한 뒤 한 번 더 해 순서와 상관없이 같은 결과가 나오게 한다.
        for (Loaded each : retry) {
            Outcome outcome = applyInOwnTransaction(each, false);
            if (outcome == null) {
                skipped++;
            } else {
                record(tally, applied, each, outcome);
            }
        }

        transactionTemplate.executeWithoutResult(status -> linkParents(applied, readCodes));

        Result result = new Result(tally.getOrDefault(Outcome.CREATED, 0), tally.getOrDefault(Outcome.UPDATED, 0),
                tally.getOrDefault(Outcome.UNCHANGED, 0), hidden, skipped);
        log.info("기업 데이터 가져오기: 새로 {}, 갱신 {}, 그대로 {}, 숨김 {}, 건너뜀 {}",
                result.created(), result.updated(), result.unchanged(), result.hidden(), result.skipped());
        return result;
    }

    private static void record(Map<Outcome, Integer> tally, List<CompanyDataPackage> applied, Loaded each,
                               Outcome outcome) {
        tally.merge(outcome, 1, Integer::sum);
        applied.add(each.pkg());
        log.info("기업 데이터 반영: file={}, code={}, 결과={}", each.fileName(), each.pkg().code(), outcome);
    }

    /** 기업 하나 = 트랜잭션 하나. 실패하면 그 기업만 롤백하고 null을 돌려준다. */
    private Outcome applyInOwnTransaction(Loaded each, boolean firstTry) {
        try {
            return transactionTemplate.execute(status -> apply(each));
        } catch (RuntimeException e) {
            // DB 예외 메시지는 넣으려던 값을 담는다. 예외 종류만 남긴다.
            if (firstTry) {
                log.warn("기업 데이터를 반영하지 못해 나머지를 반영한 뒤 한 번 더 해봅니다. file={}, code={}, 오류={}",
                        each.fileName(), each.pkg().code(), e.getClass().getSimpleName());
            } else {
                log.error("기업 데이터를 반영하지 못해 건너뜁니다. file={}, code={}, 오류={}",
                        each.fileName(), each.pkg().code(), e.getClass().getSimpleName());
            }
            return null;
        }
    }

    /** 숨긴 기업이 들고 있는 DART 고유번호·사업자등록번호를 이번 묶음이 쓰면 놓게 한다. 숨긴 행은 지우지 않는다. */
    private void releaseClaimedIdentifiers(List<Loaded> loaded) {
        List<String> dartCorpCodes = loaded.stream()
                .map(each -> each.pkg().dartCorpCode()).filter(Objects::nonNull).toList();
        List<String> businessRegNos = loaded.stream()
                .map(each -> each.pkg().businessRegNo()).filter(Objects::nonNull).toList();
        int released = 0;
        if (!dartCorpCodes.isEmpty()) {
            released += companyRepository.releaseHiddenDartCorpCodes(dartCorpCodes);
        }
        if (!businessRegNos.isEmpty()) {
            released += companyRepository.releaseHiddenBusinessRegNos(businessRegNos);
        }
        if (released > 0) {
            log.info("숨긴 기업이 들고 있던 식별자를 이번 기업 데이터에 넘겼습니다. {}건", released);
        }
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
                // 법인 식별번호 값은 남기지 않고 어느 키가 겹쳤는지만 남긴다.
                log.error("다른 기업 데이터 파일과 겹쳐 건너뜁니다. file={}, code={}, 겹친 키={}", each.fileName(),
                        each.pkg().code(), duplicated.stream().map(key -> key.substring(0, key.indexOf('='))).toList());
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
     * <p>다시 넣으면 fact_id가 바뀐다(시퀀스라 지난 id를 다시 쓰지 않는다). 이미 만든 면접은 스냅샷 안의 factId를 쓰므로
     * 영향이 없다. 기존 기업은 행을 FOR UPDATE로 잡은 뒤 고친다 — 스냅샷이 반쯤 바뀐 기업을 읽지 못하게 한다.
     */
    private Outcome apply(Loaded loaded) {
        CompanyDataPackage pkg = loaded.pkg();
        CompanyEntity company = companyRepository.findForUpdateByCode(pkg.code()).orElse(null);
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
        company.setSample(isSample(pkg));
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

    /**
     * 묶음 규칙 8. 모회사는 이번 묶음에 있어야 잇는다. 묶음에 아예 없으면 연결만 빼고 경고한다.
     *
     * <p>모회사 파일이 묶음에 있는데 검증·반영에 실패했으면 그 기업 행은 기존 상태로 남는다. 그때는 연결도 지금 그대로 둔다 —
     * 모회사 파일 하나가 잠깐 틀렸다고 자회사의 연결까지 끊지 않는다.
     *
     * <p>자기 자신을 가리키거나 따라가다 자기에게 돌아오는 연결은 저장하지 않는다. 목록에 자기 이름이 모회사로 나가거나,
     * 모회사를 따라가는 코드가 끝없이 돈다. 파일 자체는 반영한다.
     */
    private void linkParents(List<CompanyDataPackage> applied, Set<String> readCodes) {
        Map<String, String> intended = new HashMap<>();
        applied.forEach(pkg -> intended.put(pkg.code(), pkg.parentCode()));
        for (CompanyDataPackage pkg : applied) {
            CompanyEntity company = companyRepository.findByCode(pkg.code()).orElseThrow();
            String parentCode = pkg.parentCode();
            if (parentCode == null) {
                company.setParent(null);
            } else if (parentCode.equals(pkg.code())) {
                log.warn("모회사가 자기 자신이라 연결하지 않습니다. code={}", pkg.code());
                company.setParent(null);
            } else if (intended.containsKey(parentCode)) {
                if (returnsTo(pkg.code(), intended)) {
                    log.warn("모회사 연결이 돌고 돌아 자기에게 와서 연결하지 않습니다. code={}, parentCode={}",
                            pkg.code(), parentCode);
                    company.setParent(null);
                } else {
                    company.setParent(companyRepository.findByCode(parentCode).orElseThrow());
                }
            } else if (readCodes.contains(parentCode)) {
                log.warn("모회사 기업 데이터를 반영하지 못해 모회사 연결은 그대로 둡니다. code={}, parentCode={}",
                        pkg.code(), parentCode);
            } else {
                log.warn("모회사가 이번 기업 데이터에 없어 연결하지 않습니다. code={}, parentCode={}",
                        pkg.code(), parentCode);
                company.setParent(null);
            }
        }
    }

    /**
     * code에서 모회사를 따라가다 code로 돌아오는지. 이번 묶음에 있는 기업은 파일의 parentCode를, 없는 기업은 DB의 연결을 따른다.
     * 다른 데서 도는 고리에 들어가면 code로는 돌아오지 않으므로 거기서 멈춘다.
     */
    private boolean returnsTo(String code, Map<String, String> intended) {
        Set<String> visited = new HashSet<>();
        String current = intended.get(code);
        while (current != null) {
            if (current.equals(code)) {
                return true;
            }
            if (!visited.add(current)) {
                return false;
            }
            current = intended.containsKey(current)
                    ? intended.get(current)
                    : companyRepository.findByCode(current)
                            .map(CompanyEntity::getParent)
                            .map(CompanyEntity::getCode)
                            .orElse(null);
        }
        return false;
    }

    /** 설정으로 내린 기업을 숨긴다. 행은 면접이 가리키고 있어 지우지 않고, 이미 만든 면접의 스냅샷도 그대로다. */
    private int hide(Set<String> hiddenCodes) {
        if (hiddenCodes.isEmpty()) {
            return 0;
        }
        int hidden = 0;
        for (CompanyEntity company : companyRepository.findAllByCodeIn(hiddenCodes)) {
            if (company.getStatus() != CompanyStatus.HIDDEN) {
                company.setStatus(CompanyStatus.HIDDEN);
                hidden++;
                log.info("설정에 따라 기업을 숨겼습니다. code={}", company.getCode());
            }
        }
        return hidden;
    }

    /** 공개 중인데 이번에 파일이 없는 기업. 숨기지 않는다 — 내리려면 hidden-codes에 넣는다. */
    private void warnMissing(Set<String> readCodes, Set<String> hiddenCodes) {
        List<String> missing = companyRepository.findAllByStatus(CompanyStatus.PUBLISHED).stream()
                .map(CompanyEntity::getCode)
                .filter(code -> !readCodes.contains(code) && !hiddenCodes.contains(code))
                .sorted()
                .toList();
        if (!missing.isEmpty()) {
            log.warn("공개 중인 기업의 데이터 파일이 없습니다. 숨기지 않고 기존 정보로 둡니다. 내리려면 app.company.hidden-codes에 "
                    + "넣으세요. codes={}", missing);
        }
    }

    // 출처가 모두 SAMPLE이면 개발용 샘플이다. 검증이 실제 출처에는 검수 기록을 요구하므로 둘은 겹치지 않는다.
    static boolean isSample(CompanyDataPackage pkg) {
        return pkg.sources() != null && !pkg.sources().isEmpty() && pkg.sources().stream()
                .allMatch(source -> source != null && source.sourceType() == CompanySourceType.SAMPLE);
    }

    static Set<String> parseCodes(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::strip)
                .filter(code -> !code.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
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
    }
}
