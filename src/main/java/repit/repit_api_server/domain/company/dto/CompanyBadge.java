package repit.repit_api_server.domain.company.dto;

/** 면접·피드백 응답에 붙는 기업 표시. 기업을 고르지 않은 면접은 null이다. */
public record CompanyBadge(Long companyId, String displayName) {

    public static CompanyBadge from(CompanySnapshot snapshot) {
        return snapshot == null ? null : new CompanyBadge(Long.valueOf(snapshot.companyId()), snapshot.displayName());
    }
}
