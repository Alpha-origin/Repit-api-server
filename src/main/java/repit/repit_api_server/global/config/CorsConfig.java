package repit.repit_api_server.global.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

@Configuration
public class CorsConfig {

    // WebMvcConfigurer 대신 필터로 등록한다. DispatcherServlet 안에서 CORS를 처리하면
    // 405처럼 핸들러 매핑 단계에서 예외가 나는 응답에 헤더가 붙지 않아 브라우저가 CORS 에러로 표시한다.
    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(
                "http://localhost:5173",
                "https://repit-web-psi.vercel.app",
                "https://team-alpha.org"
                ));
        config.setAllowedMethods(List.of(
                "GET",
                "POST",
                "PUT",
                "DELETE",
                "PATCH",
                "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(1800L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(source));
        // RequestLoggingFilter 바로 뒤에 세운다. 맨 앞에 두면 여기서 거절한 요청이 체인을 타지 못해
        // 403만 나가고 로그에는 한 줄도 남지 않는다. 어느 오리진이 왜 막혔는지 볼 수 없게 된다.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }
}
