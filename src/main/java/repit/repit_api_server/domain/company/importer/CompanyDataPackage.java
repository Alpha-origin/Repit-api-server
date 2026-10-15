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

    public record Source(
            String sourceKey,
            CompanySourceType sourceType,
            String url,
            String title,
            String scope,
            LocalDate publishedAt,
            LocalDate collectedAt) {
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
    }

    public record Review(String reviewedBy, LocalDate reviewedAt) {
    }
}
