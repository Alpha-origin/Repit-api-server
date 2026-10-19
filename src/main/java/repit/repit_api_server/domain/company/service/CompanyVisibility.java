package repit.repit_api_server.domain.company.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import repit.repit_api_server.domain.company.entity.CompanyEntity;
import repit.repit_api_server.domain.company.entity.enums.CompanyStatus;

import java.util.Locale;

/**
 * 기업을 목록·신규 면접에 내보낼 수 있는지 정한다. 목록, 면접 생성 스냅샷, 모회사 이름이 같은 조건을 쓴다.
 *
 * <p>공개(PUBLISHED)이면서 사람이 검수한 기록이 있어야 한다. SAMPLE 패키지는 검수 기록이 없어도 되지만, 허용한 환경
 * (개발·스테이징)에서만 내보낸다. 가져오기도 같은 설정으로 SAMPLE을 막지만, 설정을 바꾸기 전에 이미 DB에 들어온 SAMPLE이
 * 운영 목록에 남지 않도록 여기서도 본다.
 *
 * <p>허용 여부는 app.company.allow-sample(COMPANY_ALLOW_SAMPLE)로 정한다. 비워 두면 운영 프로필(prod)이 아닐 때만 허용한다 —
 * 샘플 위치를 쓰던 개발·스테이징이 설정을 더하지 않아도 그대로 돌고, 운영은 위치를 샘플 폴더로 잘못 적어도 샘플이 공개되지 않는다.
 */
@Component
public class CompanyVisibility {

    private static final Logger log = LoggerFactory.getLogger(CompanyVisibility.class);

    // RequiredSettingsCheck.PRODUCTION_PROFILE과 같은 값이다. 이 프로필이 켜져 있으면 운영으로 본다.
    static final String PRODUCTION_PROFILE = "prod";

    private final boolean allowSample;

    @Autowired
    public CompanyVisibility(@Value("${app.company.allow-sample:}") String allowSample, Environment environment) {
        boolean production = environment.matchesProfiles(PRODUCTION_PROFILE);
        this.allowSample = resolve(allowSample, production);
        log.info("SAMPLE 기업 허용={} (설정값=\"{}\", 운영 프로필={})", this.allowSample,
                allowSample == null ? "" : allowSample.strip(), production);
    }

    public CompanyVisibility(boolean allowSample) {
        this.allowSample = allowSample;
    }

    /** 비어 있으면 운영이 아닐 때만 허용한다. true·false 말고 다른 값은 잘못 적은 것이라 서버를 띄우지 않는다. */
    static boolean resolve(String value, boolean production) {
        if (value == null || value.isBlank()) {
            return !production;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalStateException(
                    "COMPANY_ALLOW_SAMPLE(app.company.allow-sample)은 true, false 또는 빈 값이어야 합니다: " + value);
        };
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
