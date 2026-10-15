package repit.repit_api_server.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * 빠지면 서버가 떠도 제대로 일하지 못하는 설정을 기동할 때 확인한다. 어기면 서버가 뜨지 않는다.
 *
 * <p>콜백 주소가 틀리면 분석·채팅 서버의 결과가 엉뚱한 곳으로 가거나 아예 오지 않는다. 그런데 요청은 202로 접수돼
 * 겉으로는 정상처럼 보이고, 실패는 몇 분 뒤 시간 초과로만 드러난다. 블루/그린 배포라 새 서버가 뜨지 않으면 이전 서버가
 * 그대로 남으므로, 잘못된 설정은 배포 시점에 막는 편이 낫다. 연결까지 시험하지는 않는다 — 외부 서버가 잠깐 멈췄다고
 * 기동을 막으면 안 된다.
 */
@Component
class RequiredSettingsCheck {

    // 이 프로필이 켜져 있으면 운영으로 본다. 개발용 주소(ngrok, localhost)를 콜백 주소로 받지 않는다.
    static final String PRODUCTION_PROFILE = "prod";

    RequiredSettingsCheck(@Value("${app.callback-base-url:}") String callbackBaseUrl,
                          @Value("${app.internal-auth.required:false}") boolean internalAuthRequired,
                          @Value("${app.internal-auth.token:}") String internalAuthToken,
                          Environment environment) {
        verifyCallbackBaseUrl(callbackBaseUrl, environment.matchesProfiles(PRODUCTION_PROFILE));
        verifyInternalAuth(internalAuthRequired, internalAuthToken);
    }

    /**
     * 분석·채팅 서버가 결과를 보낼 이 서버의 외부 주소. 뒤에 콜백 경로를 바로 붙이므로 쿼리·조각·끝 슬래시가 없어야 한다.
     * 끝 슬래시가 있으면 "//api/..."가 되어 시큐리티 방화벽이 콜백을 모두 400으로 막는다.
     */
    static void verifyCallbackBaseUrl(String value, boolean production) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("CALLBACK_BASE_URL(app.callback-base-url)이 비어 있습니다. "
                    + "분석·채팅 서버가 결과를 보낼 이 서버의 외부 주소를 넣어 주세요. 예: https://api.repit.example");
        }
        URI uri;
        try {
            uri = new URI(value.trim());
        } catch (URISyntaxException e) {
            throw invalid(value, "주소 형식이 아닙니다");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw invalid(value, "http 또는 https 주소여야 합니다");
        }
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || value.trim().endsWith("/")) {
            throw invalid(value, "호스트가 있고 사용자 정보·쿼리·조각·끝 슬래시가 없어야 합니다");
        }
        if (!production) {
            return;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https")) {
            throw invalid(value, "운영에서는 https 주소여야 합니다");
        }
        if (host.contains("ngrok") || isLoopback(host)) {
            throw invalid(value, "운영에서는 ngrok이나 localhost 같은 개발용 주소를 쓸 수 없습니다");
        }
    }

    /**
     * 내부 인증을 필수로 켰는데 인증값이 비어 있으면 막는다. 비어 있으면 콜백을 인증 없이 받는 지금 동작으로 조용히
     * 돌아가, 켰다고 믿는 동안 아무나 콜백을 보낼 수 있다. 인증값은 오류 메시지에 싣지 않는다.
     */
    static void verifyInternalAuth(boolean required, String token) {
        if (required && (token == null || token.isBlank())) {
            throw new IllegalStateException("INTERNAL_AUTH_REQUIRED가 true인데 INTERNAL_AUTH_TOKEN(app.internal-auth.token)이 "
                    + "비어 있습니다. 분석·채팅 서버와 같은 값을 넣거나, 아직 헤더를 보내지 않는다면 INTERNAL_AUTH_REQUIRED를 끄세요.");
        }
    }

    private static boolean isLoopback(String host) {
        return host.equals("localhost") || host.endsWith(".localhost") || host.startsWith("127.")
                || host.equals("[::1]") || host.equals("0.0.0.0");
    }

    private static IllegalStateException invalid(String value, String reason) {
        return new IllegalStateException("CALLBACK_BASE_URL(app.callback-base-url)이 올바르지 않습니다: " + value
                + " — " + reason + ".");
    }
}
