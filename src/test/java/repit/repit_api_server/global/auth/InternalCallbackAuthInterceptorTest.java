package repit.repit_api_server.global.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;
import repit.repit_api_server.global.exception.BusinessException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 서버 간 콜백을 지키는 자리.
 *
 * <p>이 경로들은 사용자 토큰 없이 불린다. 열어두면 면접 기록을 통째로 갈아치우는 요청까지
 * 누구나 보낼 수 있어, 서버끼리 나눠 가진 값으로 막는다.
 */
class InternalCallbackAuthInterceptorTest {

    private HttpServletRequest request(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/interviews/result");
        if (token != null) {
            request.addHeader(InternalCallbackAuthInterceptor.HEADER, token);
        }
        return request;
    }

    private HttpServletResponse response() {
        return new MockHttpServletResponse();
    }

    @Test
    void 맞는_값을_들고_오면_통과시킨다() {
        InternalCallbackAuthInterceptor interceptor = new InternalCallbackAuthInterceptor("secret");

        assertThat(interceptor.preHandle(request("secret"), response(), new Object())).isTrue();
    }

    @Test
    void 값이_없으면_401로_막는다() {
        InternalCallbackAuthInterceptor interceptor = new InternalCallbackAuthInterceptor("secret");

        assertThatThrownBy(() -> interceptor.preHandle(request(null), response(), new Object()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 값이_다르면_403으로_막는다() {
        InternalCallbackAuthInterceptor interceptor = new InternalCallbackAuthInterceptor("secret");

        assertThatThrownBy(() -> interceptor.preHandle(request("다른 값"), response(), new Object()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * 부르는 쪽이 헤더를 보내도록 배포한 뒤에 이 서버에서 강제해야 한다. 값이 없다고 곧바로
     * 막으면 그 순서를 지킬 수 없고, 그 사이의 콜백은 통째로 튕겨 결과가 폐기된다.
     */
    @Test
    void 값을_설정하지_않았으면_아직_강제하지_않는다() {
        InternalCallbackAuthInterceptor interceptor = new InternalCallbackAuthInterceptor("");

        assertThat(interceptor.preHandle(request(null), response(), new Object())).isTrue();
    }

    /**
     * 영상 분석 콜백은 어긋난 본문까지 원문으로 남긴다. 값이 없다고 열어 두면 누구나 반복 호출로 DB에 임의의
     * 본문을 쌓을 수 있다. 받지 못한 결과는 작업 조회로 되찾으므로 막아도 잃는 것이 없다.
     */
    @Test
    void 값을_설정하지_않았어도_영상_분석_콜백은_막는다() {
        InternalCallbackAuthInterceptor interceptor = new InternalCallbackAuthInterceptor("");
        MockHttpServletRequest byUri = new MockHttpServletRequest("POST", "/api/analyses/video/callback");
        // 핸들러를 고른 패턴이 기준이다. 요청 URI가 달라 보여도 같은 핸들러로 가면 막는다.
        MockHttpServletRequest byPattern = new MockHttpServletRequest("POST", "/api/analyses/video/callback/");
        byPattern.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/analyses/video/callback");

        for (MockHttpServletRequest request : List.of(byUri, byPattern)) {
            assertThatThrownBy(() -> interceptor.preHandle(request, response(), new Object()))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
        }
    }

    /** 배포 과정에서 앞뒤 공백이 섞여 들어오는 일이 있다. 그것 때문에 콜백이 통째로 막히면 안 된다. */
    @Test
    void 앞뒤_공백은_값의_일부로_보지_않는다() {
        InternalCallbackAuthInterceptor interceptor = new InternalCallbackAuthInterceptor("secret");

        assertThat(interceptor.preHandle(request("  secret  "), response(), new Object())).isTrue();
    }
}
