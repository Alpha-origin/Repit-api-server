package repit.repit_api_server.domain.company.entity.enums;

/**
 * 기업 행은 지우지 않는다. HIDDEN은 목록·면접 생성에서 보이지 않고, 이미 만든 면접의 스냅샷은 그대로 둔다.
 *
 * <p>HIDDEN은 설정(app.company.hidden-codes)으로만 만든다. 패키지 파일이 디렉터리에서 빠졌다고 바꾸지 않는다.
 */
public enum CompanyStatus {
    PUBLISHED,
    HIDDEN
}
