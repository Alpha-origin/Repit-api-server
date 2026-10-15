package repit.repit_api_server.domain.company.dto;

import repit.repit_api_server.domain.company.entity.enums.CompanyCategory;
import repit.repit_api_server.domain.company.entity.enums.LegalSize;

import java.time.LocalDate;
import java.util.List;

/** GET /api/companies 한 건. */
public record CompanySummaryResponse(
        Long companyId,
        String displayName,
        String legalName,
        String parentDisplayName,
        CompanyCategory category,
        // 미확인이면 null이다.
        LegalSize legalSize,
        // TALENT fact의 title. 표시 순서대로 최대 3개.
        List<String> talentKeywords,
        LocalDate dataAsOf) {
}
