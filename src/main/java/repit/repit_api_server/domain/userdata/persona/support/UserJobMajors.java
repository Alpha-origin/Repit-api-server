package repit.repit_api_server.domain.userdata.persona.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Major;

import java.util.Locale;

/**
 * 인증 서버의 사용자 직무 값을 다룬다.
 *
 * <p>기술 면접관의 전공은 사용자가 고르지 않고 사용자 직무를 따른다. 면접관 전공은
 * BACKEND / FRONTEND 두 가지뿐이라, 그 밖의 직무는 가까운 값이나 대체값으로 맞춘다.
 */
public final class UserJobMajors {

    private static final Logger log = LoggerFactory.getLogger(UserJobMajors.class);

    // 인증 서버의 직무 값 앞에 붙는 접두사(MAJOR_BACKEND).
    private static final String AUTH_MAJOR_PREFIX = "MAJOR_";

    private UserJobMajors() {}

    /** MAJOR_ 접두사를 떼고 앞뒤 공백을 지운 뒤 대문자로 맞춘다. 비어 있으면 null. */
    public static String normalize(String userMajor) {
        if (userMajor == null) {
            return null;
        }
        String value = userMajor.strip().toUpperCase(Locale.ROOT);
        if (value.startsWith(AUTH_MAJOR_PREFIX)) {
            value = value.substring(AUTH_MAJOR_PREFIX.length());
        }
        return value.isEmpty() ? null : value;
    }

    /**
     * 기술 면접관 전공. null을 돌려주지 않는다.
     *
     * <p>WEB_PUBLISHER는 화면 개발 직무라 FRONTEND로 맞춘다. 직무가 없거나 옮길 수 없는 값은
     * BACKEND로 둔다. 마이페이지 직무 저장이 아직 없어 직무 없는 사용자가 정상 경로에 있으므로 막지 않는다.
     */
    public static Major toPersonaMajor(String userMajor) {
        String job = normalize(userMajor);
        if (job == null) {
            log.info("사용자 직무가 없어 기술 면접관 전공을 BACKEND로 둡니다.");
            return Major.BACKEND;
        }
        return switch (job) {
            case "BACKEND" -> Major.BACKEND;
            case "FRONTEND", "WEB_PUBLISHER" -> Major.FRONTEND;
            default -> {
                log.info("면접관 전공으로 옮길 수 없는 직무라 BACKEND로 둡니다. userMajor={}", userMajor);
                yield Major.BACKEND;
            }
        };
    }
}
