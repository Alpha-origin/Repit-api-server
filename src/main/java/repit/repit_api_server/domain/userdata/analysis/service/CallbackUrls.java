package repit.repit_api_server.domain.userdata.analysis.service;

import repit.repit_api_server.global.exception.BusinessException;

import java.net.URI;

/**
 * 분석 서버에 넘기는 결과 수신 주소. https여야 하고 사용자 정보나 조각(fragment)이 붙으면 안 된다.
 *
 * <p>어기면 분석 서버가 요청을 422로 거절한다. 보내기 전에 걸러내 무엇이 잘못됐는지 남긴다.
 */
final class CallbackUrls {

    private CallbackUrls() {
    }

    static String require(String baseUrl, String path) {
        String url = baseUrl + path;
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw BusinessException.unprocessable("분석 콜백 주소가 올바르지 않습니다: " + url);
        }
        boolean allowedPort = uri.getPort() == -1 || uri.getPort() == 443;
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || !allowedPort
                || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw BusinessException.unprocessable("분석 콜백 주소는 사용자 정보 없는 https 주소여야 합니다: " + url);
        }
        return url;
    }
}
