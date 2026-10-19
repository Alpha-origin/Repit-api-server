package repit.repit_api_server.domain.company.importer;

import repit.repit_api_server.domain.company.entity.enums.CompanyCategory;
import repit.repit_api_server.domain.company.entity.enums.CompanyFactKind;
import repit.repit_api_server.domain.company.entity.enums.CompanySourceType;
import repit.repit_api_server.domain.company.entity.enums.LegalSize;
import repit.repit_api_server.domain.company.entity.enums.SignalsOrigin;

import java.time.LocalDate;
import java.util.List;

/**
 * 기업 데이터 패키지 파일 하나(schemaVersion 1). 파일 하나가 기업 하나다.
 *
 * <p>데이터셋과 서버가 만나는 유일한 형식이라, 데이터셋을 어떻게 만들든 이 모양이면 코드 변경 없이 바뀐다.
 * 모르는 필드는 무시한다. 코드값이 모르는 값이면 파일을 읽지 못해 그 파일만 건너뛴다.
 */
public record CompanyDataPackage(
        Integer schemaVersion,
        String code,
        String displayName,
        String legalName,
        List<String> aliases,
        String parentCode,
        CompanyCategory category,
        LegalSize legalSize,
        String legalSizeSource,
        String dartCorpCode,
        String businessRegNo,
        LocalDate dataAsOf,
        List<Source> sources,
        List<Fact> facts,
        String interviewerBrief,
        Review review) {

    public CompanyDataPackage {
        aliases = aliases == null ? List.of() : aliases;
    }

    /**
     * 글 필드의 앞뒤 공백을 뺀 사본. 검증과 저장이 같은 값을 보게 한다 — 검증은 공백을 뺀 길이로 상한을 보는데(00 §2) 저장이
     * 공백 붙은 원래 값을 넣으면, 검증을 통과한 파일이 VARCHAR 길이에 걸려 저장 단계에서 실패한다.
     *
     * <p>내용은 자르지 않는다. code·parentCode·법인 식별번호·주소는 형식 규칙으로 보는 값이라 그대로 둔다. 빈 항목(null)은
     * 검증이 잡도록 그대로 남긴다.
     */
    public CompanyDataPackage normalized() {
        return new CompanyDataPackage(schemaVersion, code, strip(displayName), strip(legalName), stripAll(aliases),
                parentCode, category, legalSize, strip(legalSizeSource), dartCorpCode, businessRegNo, dataAsOf,
                sources == null ? null : sources.stream().map(source -> source == null ? null : source.normalized()).toList(),
                facts == null ? null : facts.stream().map(fact -> fact == null ? null : fact.normalized()).toList(),
                strip(interviewerBrief),
                review == null ? null : new Review(strip(review.reviewedBy()), review.reviewedAt()));
    }

    private static String strip(String value) {
        return value == null ? null : value.strip();
    }

    private static List<String> stripAll(List<String> values) {
        return values == null ? null : values.stream().map(CompanyDataPackage::strip).toList();
    }

    public record Source(
            String sourceKey,
            CompanySourceType sourceType,
            String url,
            String title,
            String scope,
            LocalDate publishedAt,
            LocalDate collectedAt) {

        Source normalized() {
            return new Source(strip(sourceKey), sourceType, url, strip(title), strip(scope), publishedAt, collectedAt);
        }
    }

    public record Fact(
            CompanyFactKind kind,
            String title,
            String content,
            String sourceKey,
            List<String> positiveSignals,
            List<String> concernSignals,
            SignalsOrigin signalsOrigin) {

        public Fact {
            positiveSignals = positiveSignals == null ? List.of() : positiveSignals;
            concernSignals = concernSignals == null ? List.of() : concernSignals;
        }

        boolean hasSignals() {
            return !positiveSignals.isEmpty() || !concernSignals.isEmpty();
        }

        Fact normalized() {
            return new Fact(kind, strip(title), strip(content), strip(sourceKey), stripAll(positiveSignals),
                    stripAll(concernSignals), signalsOrigin);
        }
    }

    public record Review(String reviewedBy, LocalDate reviewedAt) {
    }
}
