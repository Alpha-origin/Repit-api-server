package repit.repit_api_server.domain.company.importer;

import repit.repit_api_server.domain.company.CompanyLimits;
import repit.repit_api_server.domain.company.entity.enums.CompanyFactKind;
import repit.repit_api_server.domain.company.entity.enums.CompanySourceType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import static repit.repit_api_server.domain.company.CompanyLimits.length;

/**
 * 패키지 파일 하나에 적용하는 검증(02 §3-2 규칙 1~5, 7). 하나라도 어기면 그 파일만 건너뛴다.
 *
 * <p>묶음 전체를 봐야 하는 규칙(6 유일성, 8 모회사)은 모든 파일을 읽은 뒤 가져오기가 본다. 모회사를 자기 자신으로 둔
 * 파일도 여기서 막지 않는다 — 관계만 저장하지 않으면 되는 일이라 가져오기가 연결을 빼고 경고한다.
 * 여기를 통과한 데이터는 분석 서버에서 422가 나지 않는다 — 상한이 분석 서버 계약과 같다.
 *
 * <p>오류 문구는 로그에 남는다. 파일의 값(주소·법인 식별번호·문장)은 싣지 않고 어느 필드가 어떤 규칙을 어겼는지만 쓴다.
 */
public final class CompanyDataValidator {

    static final int SCHEMA_VERSION = 1;

    private static final Pattern CODE = Pattern.compile("^[a-z0-9][a-z0-9-]{1,39}$");
    private static final Pattern DART_CORP_CODE = Pattern.compile("^\\d{8}$");
    private static final Pattern BUSINESS_REG_NO = Pattern.compile("^\\d{10}$");
    // 사실 오인("공식", "기출")과 소속 사칭("저희", "우리 회사")을 막는다.
    private static final List<String> FORBIDDEN_WORDS = List.of("공식", "기출", "저희", "우리 회사", "합격");

    private CompanyDataValidator() {
    }

    public static List<String> validate(String fileName, CompanyDataPackage pkg) {
        List<String> errors = new ArrayList<>();
        // 형식이 다르면 나머지 규칙은 의미가 없다.
        if (!Objects.equals(pkg.schemaVersion(), SCHEMA_VERSION)) {
            errors.add("schemaVersion이 " + SCHEMA_VERSION + "이 아닙니다: " + pkg.schemaVersion());
            return errors;
        }

        // 1. 필수 필드와 길이·개수 상한
        if (pkg.code() == null || !CODE.matcher(pkg.code()).matches()) {
            errors.add("code는 소문자·숫자·하이픈 2~40자여야 합니다.");
        }
        text(errors, "displayName", pkg.displayName(), CompanyLimits.DISPLAY_NAME, true);
        text(errors, "legalName", pkg.legalName(), 100, true);
        count(errors, "aliases", pkg.aliases(), 0, 10);
        for (int i = 0; i < pkg.aliases().size(); i++) {
            text(errors, "aliases[" + i + "]", pkg.aliases().get(i), 30, true);
        }
        required(errors, "category", pkg.category());
        pattern(errors, "dartCorpCode", pkg.dartCorpCode(), DART_CORP_CODE, "8자리 숫자");
        pattern(errors, "businessRegNo", pkg.businessRegNo(), BUSINESS_REG_NO, "10자리 숫자");
        required(errors, "dataAsOf", pkg.dataAsOf());
        if (pkg.review() != null) {
            // 컬럼이 VARCHAR(50)이다. 넘으면 검증 오류 대신 저장 단계의 예외로만 드러난다.
            text(errors, "review.reviewedBy", pkg.review().reviewedBy(), 50, false);
        }
        text(errors, "interviewerBrief", pkg.interviewerBrief(), CompanyLimits.INTERVIEWER_BRIEF, true);

        List<CompanyDataPackage.Source> sources = present(errors, "sources", pkg.sources(), 1, 30);
        for (int i = 0; i < sources.size(); i++) {
            CompanyDataPackage.Source source = sources.get(i);
            String at = "sources[" + i + "].";
            text(errors, at + "sourceKey", source.sourceKey(), 40, true);
            required(errors, at + "sourceType", source.sourceType());
            if (source.url() == null || !source.url().startsWith("https://")) {
                errors.add(at + "url은 https:// 주소여야 합니다.");
            }
            text(errors, at + "title", source.title(), 200, false);
            text(errors, at + "scope", source.scope(), 100, false);
            required(errors, at + "collectedAt", source.collectedAt());
        }

        List<CompanyDataPackage.Fact> facts = present(errors, "facts", pkg.facts(), 1, CompanyLimits.FACTS);
        for (int i = 0; i < facts.size(); i++) {
            CompanyDataPackage.Fact fact = facts.get(i);
            String at = "facts[" + i + "].";
            required(errors, at + "kind", fact.kind());
            text(errors, at + "title", fact.title(), CompanyLimits.FACT_TITLE, true);
            text(errors, at + "content", fact.content(), CompanyLimits.FACT_CONTENT, true);
            required(errors, at + "sourceKey", fact.sourceKey());
            signals(errors, at + "positiveSignals", fact.positiveSignals());
            signals(errors, at + "concernSignals", fact.concernSignals());
        }

        // 2. 파일 이름
        if (pkg.code() != null && !(pkg.code() + ".json").equals(fileName)) {
            errors.add("파일 이름이 code와 다릅니다: " + fileName + " (code=" + pkg.code() + ")");
        }

        // 3. 공개 조건
        Set<CompanyFactKind> kinds = new HashSet<>();
        facts.forEach(fact -> kinds.add(fact.kind()));
        if (!kinds.contains(CompanyFactKind.TALENT)) {
            errors.add("TALENT fact가 하나 이상 있어야 합니다.");
        }
        if (!kinds.contains(CompanyFactKind.BUSINESS)) {
            errors.add("BUSINESS fact가 하나 이상 있어야 합니다.");
        }

        // 4. 출처 연결
        Set<String> sourceKeys = new HashSet<>();
        for (int i = 0; i < sources.size(); i++) {
            String sourceKey = sources.get(i).sourceKey();
            if (sourceKey != null && !sourceKeys.add(sourceKey)) {
                errors.add("sourceKey가 겹칩니다: sources[" + i + "]");
            }
        }
        for (int i = 0; i < facts.size(); i++) {
            String sourceKey = facts.get(i).sourceKey();
            if (sourceKey != null && !sourceKeys.contains(sourceKey)) {
                errors.add("facts[" + i + "].sourceKey가 sources에 없습니다.");
            }
        }

        // 5. 신호
        for (int i = 0; i < facts.size(); i++) {
            CompanyDataPackage.Fact fact = facts.get(i);
            if (fact.kind() != CompanyFactKind.TALENT && fact.hasSignals()) {
                errors.add("facts[" + i + "]는 TALENT가 아니라 신호를 가질 수 없습니다.");
            }
            if (fact.hasSignals() && fact.signalsOrigin() == null) {
                errors.add("facts[" + i + "]에 신호가 있으면 signalsOrigin이 있어야 합니다.");
            }
        }

        // 6. 규모
        if (pkg.legalSize() != null && isBlank(pkg.legalSizeSource())) {
            errors.add("legalSize가 있으면 legalSizeSource가 있어야 합니다.");
        }

        // 7. 금지 표현
        for (int i = 0; i < facts.size(); i++) {
            CompanyDataPackage.Fact fact = facts.get(i);
            String at = "facts[" + i + "].";
            forbidden(errors, at + "title", fact.title());
            forbidden(errors, at + "content", fact.content());
            fact.positiveSignals().forEach(signal -> forbidden(errors, at + "positiveSignals", signal));
            fact.concernSignals().forEach(signal -> forbidden(errors, at + "concernSignals", signal));
        }
        forbidden(errors, "interviewerBrief", pkg.interviewerBrief());

        // 9. 검수. 샘플이 아닌 실제 데이터는 사람이 확인한 기록이 있어야 한다.
        boolean real = sources.stream().anyMatch(source -> source.sourceType() != CompanySourceType.SAMPLE);
        CompanyDataPackage.Review review = pkg.review();
        if (real && (review == null || isBlank(review.reviewedBy()) || review.reviewedAt() == null)) {
            errors.add("샘플이 아닌 출처가 있으면 review.reviewedBy와 review.reviewedAt이 있어야 합니다.");
        }
        return errors;
    }

    /** 비어 있지 않은 원소만 돌려준다. 개수와 빈 원소는 오류로 남긴다. */
    private static <T> List<T> present(List<String> errors, String field, List<T> values, int min, int max) {
        if (values == null) {
            errors.add(field + " 값이 없습니다.");
            return List.of();
        }
        count(errors, field, values, min, max);
        if (values.stream().anyMatch(Objects::isNull)) {
            errors.add(field + "에 빈 항목이 있습니다.");
        }
        return values.stream().filter(Objects::nonNull).toList();
    }

    private static void signals(List<String> errors, String field, List<String> signals) {
        count(errors, field, signals, 0, CompanyLimits.SIGNALS_PER_FACT);
        for (int i = 0; i < signals.size(); i++) {
            text(errors, field + "[" + i + "]", signals.get(i), CompanyLimits.SIGNAL, true);
        }
    }

    // 실행 입력은 앞뒤 공백을 뺀 뒤 센다(00 §2). 스냅샷도 같은 값을 싣는다.
    private static void text(List<String> errors, String field, String value, int max, boolean required) {
        if (isBlank(value)) {
            if (required) {
                errors.add(field + " 값이 없습니다.");
            }
            return;
        }
        if (length(value.strip()) > max) {
            errors.add(field + "는 " + max + "자 이하여야 합니다: " + length(value.strip()) + "자");
        }
    }

    private static void count(List<String> errors, String field, Collection<?> values, int min, int max) {
        if (values.size() < min || values.size() > max) {
            errors.add(field + "는 " + min + "~" + max + "개여야 합니다: " + values.size() + "개");
        }
    }

    private static void required(List<String> errors, String field, Object value) {
        if (value == null) {
            errors.add(field + " 값이 없습니다.");
        }
    }

    private static void pattern(List<String> errors, String field, String value, Pattern pattern, String rule) {
        if (value != null && !pattern.matcher(value).matches()) {
            errors.add(field + "는 " + rule + "여야 합니다.");
        }
    }

    private static void forbidden(List<String> errors, String field, String value) {
        if (value == null) {
            return;
        }
        for (String word : FORBIDDEN_WORDS) {
            if (value.contains(word)) {
                errors.add(field + "에 쓸 수 없는 표현이 있습니다: \"" + word + "\"");
            }
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
