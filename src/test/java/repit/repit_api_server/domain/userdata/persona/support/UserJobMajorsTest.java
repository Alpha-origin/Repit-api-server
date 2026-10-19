package repit.repit_api_server.domain.userdata.persona.support;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Major;

import static org.assertj.core.api.Assertions.assertThat;

class UserJobMajorsTest {

    @ParameterizedTest
    @CsvSource({
            "MAJOR_BACKEND,     BACKEND,       BACKEND",
            "MAJOR_FRONTEND,    FRONTEND,      FRONTEND",
            "FRONTEND,          FRONTEND,      FRONTEND",
            "' major_frontend ', FRONTEND,     FRONTEND",
            "WEB_PUBLISHER,     WEB_PUBLISHER, WEB_PUBLISHER",
            "DATA_ANALYST,      DATA_ANALYST,  DATA_ANALYST",
    })
    void 직무를_정규화하고_면접관_전공으로_옮긴다(String userMajor, String normalized, Major major) {
        assertThat(UserJobMajors.normalize(userMajor)).isEqualTo(normalized);
        assertThat(UserJobMajors.toPersonaMajor(userMajor)).isEqualTo(major);
    }

    @ParameterizedTest
    @EnumSource(Major.class)
    void 모든_직군_코드를_전공으로_그대로_쓴다(Major major) {
        assertThat(UserJobMajors.toPersonaMajor("MAJOR_" + major.name())).isEqualTo(major);
    }

    /** 직무가 없는 사용자도 면접을 시작할 수 있어야 한다. 예외 대신 BACKEND로 둔다. */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  "})
    void 직무가_없으면_정규화는_null이고_전공은_백엔드다(String userMajor) {
        assertThat(UserJobMajors.normalize(userMajor)).isNull();
        assertThat(UserJobMajors.toPersonaMajor(userMajor)).isEqualTo(Major.BACKEND);
    }
}
