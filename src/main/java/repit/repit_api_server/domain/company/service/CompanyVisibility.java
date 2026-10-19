package repit.repit_api_server.domain.company.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import repit.repit_api_server.domain.company.entity.CompanyEntity;
import repit.repit_api_server.domain.company.entity.enums.CompanyStatus;

/**
 * 기업을 목록·신규 면접에 내보낼 수 있는지 정한다. 목록, 면접 생성 스냅샷, 모회사 이름이 같은 조건을 쓴다.
 *
 * <p>공개(PUBLISHED)이면서 사람이 검수한 기록이 있어야 한다. SAMPLE 패키지는 검수 기록이 없어도 되지만, 허용한 환경
 * (개발·스테이징)에서만 내보낸다. 가져오기도 같은 설정으로 SAMPLE을 막지만, 설정을 바꾸기 전에 이미 DB에 들어온 SAMPLE이
 * 운영 목록에 남지 않도록 여기서도 본다.
 */
@Component
public class CompanyVisibility {

    private final boolean allowSample;

    public CompanyVisibility(@Value("${app.company.allow-sample:false}") boolean allowSample) {
        this.allowSample = allowSample;
    }

    public boolean allowsSample() {
        return allowSample;
    }

    public boolean isSelectable(CompanyEntity company) {
        if (company == null || company.getStatus() != CompanyStatus.PUBLISHED) {
            return false;
        }
        if (company.isSample()) {
            return allowSample;
        }
        return company.getReviewedBy() != null && !company.getReviewedBy().isBlank()
                && company.getReviewedAt() != null;
    }
}
