package repit.repit_api_server.global.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import repit.repit_api_server.global.auth.InternalCallbackAuthInterceptor;

/**
 * 서버 간 콜백 경로에만 내부 인증을 건다.
 *
 * <p>여기 적힌 경로는 사용자가 아니라 분석·채팅 서버가 부르는 자리다. 사용자 토큰으로는
 * 지킬 수 없어 별도 인증을 쓰고, 반대로 사용자용 경로에는 이 인증을 걸지 않는다.
 *
 * <p>경로를 늘릴 때는 부르는 쪽이 헤더를 보내도록 먼저 배포해야 한다. 순서를 뒤집으면
 * 그 콜백이 통째로 401로 튕기고, 분석 서버는 두 번 시도한 뒤 결과를 폐기한다.
 */
@Configuration
@RequiredArgsConstructor
public class InternalAuthConfig implements WebMvcConfigurer {

    private final InternalCallbackAuthInterceptor internalCallbackAuthInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(internalCallbackAuthInterceptor)
                .addPathPatterns(
                        // 분석 서버 콜백
                        "/api/v1/ai/callback",
                        "/api/questions/tailor/callback",
                        "/api/questions/tailor/multi/callback",
                        "/api/feedbacks/callback",
                        // 채팅 서버가 면접 기록을 넘기는 자리. 질문·답변을 지우고 다시 넣은 뒤
                        // 채점까지 이어지므로 인증 없이 열어두면 남의 면접을 통째로 갈아치울 수 있다.
                        "/api/interviews/result");
    }
}
