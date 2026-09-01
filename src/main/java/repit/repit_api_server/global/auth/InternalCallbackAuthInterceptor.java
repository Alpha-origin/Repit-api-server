package repit.repit_api_server.global.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import repit.repit_api_server.global.exception.BusinessException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 서버 간 콜백을 받는 자리를 지킨다.
 *
 * <p>분석·채팅 서버가 부르는 콜백에는 사용자 토큰이 없다. 그렇다고 열어두면 면접 기록을
 * 통째로 덮어쓰는 요청까지 누구나 보낼 수 있어, 서버끼리 나눠 가진 값으로 막는다.
 *
 * <p>토큰이 설정되지 않았으면 통과시킨다. 부르는 쪽이 먼저 헤더를 실어 보내도록 배포한 뒤에
 * 이 서버에서 강제해야 하는데, 값이 없다고 곧바로 막으면 그 순서를 지킬 수 없다. 대신 지키지
 * 않고 있다는 사실은 뜨자마자 한 번 남긴다.
 */
@Component
public class InternalCallbackAuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(InternalCallbackAuthInterceptor.class);

    /** 서버 간 인증값을 싣는 헤더. 쿼리 파라미터로 받지 않는다 — URL은 로그와 프록시에 그대로 남는다. */
    public static final String HEADER = "X-Internal-Token";

    private final String token;
    private final AtomicBoolean warned = new AtomicBoolean();

    public InternalCallbackAuthInterceptor(@Value("${app.internal-auth.token:}") String token) {
        this.token = token == null ? "" : token.trim();
    }

    /**
     * 본문을 읽기 전에 막는다. 컨트롤러까지 들여보내면 인증에 실패한 요청이 면접 기록을 먼저
     * 지우고 나서 거절당한다.
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (token.isEmpty()) {
            if (warned.compareAndSet(false, true)) {
                log.warn("app.internal-auth.token이 비어 있어 서버 간 콜백을 인증 없이 받습니다. "
                        + "분석·채팅 서버가 {} 헤더를 보내기 시작하면 값을 설정해 강제하세요.", HEADER);
            }
            return true;
        }

        String presented = request.getHeader(HEADER);
        if (presented == null || presented.isBlank()) {
            log.warn("서버 간 인증값 없이 콜백이 들어와 거절했습니다. path={}", request.getRequestURI());
            throw BusinessException.unauthorized("서버 간 인증이 필요한 요청입니다.");
        }
        // 앞에서부터 한 글자씩 비교하면 맞은 글자 수만큼 응답이 늦어져, 그 차이로 값을 알아낼 수 있다.
        if (!MessageDigest.isEqual(presented.trim().getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            // 받은 값은 남기지 않는다. 틀린 값이라도 로그에 남으면 다음 시도의 실마리가 된다.
            log.warn("서버 간 인증값이 맞지 않아 콜백을 거절했습니다. path={}", request.getRequestURI());
            throw BusinessException.forbidden("서버 간 인증에 실패했습니다.");
        }
        return true;
    }
}
