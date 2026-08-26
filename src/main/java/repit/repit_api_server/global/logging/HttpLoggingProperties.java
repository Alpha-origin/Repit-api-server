package repit.repit_api_server.global.logging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * 요청/응답 로깅의 범위를 조절한다.
 *
 * @param enabled            false면 로깅 필터와 외부 호출 인터셉터가 모두 빠진다.
 * @param includeBody        본문까지 남길지 여부. 끄면 요청선/상태코드/소요시간만 남는다.
 * @param maxBodyLength      한 건의 본문에서 남길 최대 글자 수. 넘으면 잘라내고 생략 표시를 붙인다.
 * @param excludePathPatterns 로깅에서 제외할 경로. 헬스체크나 문서처럼 흐름과 무관한 호출을 걸러낸다.
 */
@ConfigurationProperties(prefix = "app.http-logging")
public record HttpLoggingProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("true") boolean includeBody,
        @DefaultValue("2000") int maxBodyLength,
        List<String> excludePathPatterns
) {

    private static final int DEFAULT_MAX_BODY_LENGTH = 2000;
    private static final List<String> DEFAULT_EXCLUDE_PATH_PATTERNS = List.of(
            "/actuator/**",
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/favicon.ico"
    );

    public HttpLoggingProperties {
        if (maxBodyLength <= 0) {
            maxBodyLength = DEFAULT_MAX_BODY_LENGTH;
        }
        if (excludePathPatterns == null || excludePathPatterns.isEmpty()) {
            excludePathPatterns = DEFAULT_EXCLUDE_PATH_PATTERNS;
        }
    }

    public static HttpLoggingProperties defaults() {
        return new HttpLoggingProperties(true, true, DEFAULT_MAX_BODY_LENGTH, DEFAULT_EXCLUDE_PATH_PATTERNS);
    }
}
