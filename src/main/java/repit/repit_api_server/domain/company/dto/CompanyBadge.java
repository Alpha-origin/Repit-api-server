package repit.repit_api_server.domain.company.dto;

/** 면접·피드백 응답에 붙는 기업 표시. 기업을 고르지 않은 면접은 null이다. */
public record CompanyBadge(Long companyId, String displayName) {

    // id는 면접의 company_id 컬럼에서 받는다. 스냅샷의 문자열 id를 다시 숫자로 바꾸지 않는다.
    public static CompanyBadge of(Long companyId, CompanySnapshot snapshot) {
        return snapshot == null ? null : new CompanyBadge(companyId, snapshot.displayName());
    }
}
