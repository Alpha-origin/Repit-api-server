package repit.repit_api_server.domain.company.entity.enums;

/** 기업 행은 지우지 않는다. 패키지에서 빠지면 HIDDEN이 되고 목록·면접 생성에서 보이지 않는다. */
public enum CompanyStatus {
    PUBLISHED,
    HIDDEN
}
