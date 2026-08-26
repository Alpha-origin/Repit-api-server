package repit.repit_api_server.global.logging;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 들어오는 요청 한 건을 진입과 종료 두 줄로 남긴다.
 * 요청마다 추적 id를 발급해 MDC에 넣어두므로, 그 요청이 남긴 모든 로그(외부 서버 호출 포함)를
 * 같은 id로 묶어 흐름 그대로 읽을 수 있다.
 */
// CorsFilter 바로 뒤에 세운다. 앞에 두면 브라우저의 사전 요청(OPTIONS)까지 흐름에 섞이고,
// 더 뒤로 밀면 그 사이 필터에서 걸린 요청이 로그에 아예 남지 않는다.
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {

    public static final String TRACE_ID = "traceId";

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    private static final String REQUEST_ARROW = "--> ";
    private static final String RESPONSE_ARROW = "<-- ";
    private static final String BODY_INDENT = "\n      body: ";

    // 이보다 큰 본문은 로그를 위해 메모리에 통째로 올리지 않는다.
    private static final long MAX_CACHEABLE_BODY_BYTES = 256 * 1024L;

    // 끝나지 않고 계속 흘러가는 응답. 로그를 위해 감쌌다가는 이벤트가 캐시에 갇힌다.
    private static final String SSE_PATH_PREFIX = "/api/v1/ai/subscribe/";

    private final HttpLoggingProperties properties;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public RequestLoggingFilter(HttpLoggingProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.enabled()) {
            return true;
        }
        String path = request.getRequestURI();
        return properties.excludePathPatterns().stream().anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String traceId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put(TRACE_ID, traceId);
        long startedAt = System.nanoTime();

        HttpServletRequest requestToUse = request;
        byte[] requestBody = null;
        if (shouldCacheRequestBody(request)) {
            CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request);
            requestToUse = cachedRequest;
            requestBody = cachedRequest.getCachedBody();
        }


        ContentCachingResponseWrapper cachingResponse =
                shouldCacheResponseBody(request) ? new ContentCachingResponseWrapper(response) : null;
        HttpServletResponse responseToUse = cachingResponse != null ? cachingResponse : response;

        logRequest(request, requestBody);

        Exception failure = null;
        try {
            filterChain.doFilter(requestToUse, responseToUse);
        } catch (Exception e) {
            failure = e;
            throw e;
        } finally {
            long elapsedMillis = elapsedMillis(startedAt);
            byte[] responseBody = cachingResponse != null ? cachingResponse.getContentAsByteArray() : null;

            // 응답을 감싼 경우 원래 응답으로 본문을 되돌려주지 않으면 클라이언트가 빈 응답을 받는다.
            if (cachingResponse != null) {
                cachingResponse.copyBodyToResponse();
            }

            if (request.isAsyncStarted()) {
                // 아직 응답이 끝나지 않았다. 여기서는 접수만 남기고 마무리는 비동기 완료 시점에 남긴다.
                log.info("{}{} {} 비동기 처리 시작 ({}ms)", RESPONSE_ARROW, request.getMethod(), request.getRequestURI(), elapsedMillis);
                registerAsyncCompletionLogging(request, responseToUse, cachingResponse, traceId, startedAt);
            } else {
                logResponse(request, responseToUse.getStatus(), responseBody, cachingResponse, elapsedMillis, failure);
            }

            MDC.remove(TRACE_ID);
        }
    }

    private void logRequest(HttpServletRequest request, byte[] requestBody) {
        StringBuilder message = new StringBuilder(REQUEST_ARROW)
                .append(request.getMethod()).append(' ').append(requestUri(request))
                .append("  client=").append(clientIp(request));

        String contentType = request.getContentType();
        if (contentType != null) {
            message.append("  type=").append(contentType);
        }
        long contentLength = request.getContentLengthLong();
        if (contentLength > 0) {
            message.append("  size=").append(LogPayloads.humanReadableSize(contentLength));
        }
        String authorization = LogPayloads.maskAuthorization(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (authorization != null) {
            message.append("  auth=").append(authorization);
        }

        appendBody(message, requestBody, contentType, charsetOf(request.getCharacterEncoding()));
        log.info(message.toString());
    }

    private void logResponse(HttpServletRequest request, int status, byte[] responseBody,
                             ContentCachingResponseWrapper cachingResponse, long elapsedMillis, Exception failure) {

        StringBuilder message = new StringBuilder(RESPONSE_ARROW)
                .append(request.getMethod()).append(' ').append(request.getRequestURI())
                .append("  ").append(statusText(status))
                .append("  (").append(elapsedMillis).append("ms)");

        if (cachingResponse != null) {
            appendBody(message, responseBody, cachingResponse.getContentType(),
                    charsetOf(cachingResponse.getCharacterEncoding()));
        }

        if (failure != null) {
            // 예외 자체는 GlobalExceptionHandler가 남긴다. 여기서는 어느 요청이 터졌는지만 이어 붙인다.
            log.error("{}  실패: {}", message, failure.toString());
            return;
        }
        if (status >= 500) {
            log.error(message.toString());
        } else if (status >= 400) {
            log.warn(message.toString());
        } else {
            log.info(message.toString());
        }
    }

    /**
     * SSE처럼 필터를 빠져나간 뒤에야 끝나는 요청은 완료 시점에 한 줄을 더 남긴다.
     * 완료 콜백은 다른 스레드에서 돌기 때문에 추적 id를 다시 심어줘야 같은 흐름으로 읽힌다.
     */
    private void registerAsyncCompletionLogging(HttpServletRequest request, HttpServletResponse response,
                                                ContentCachingResponseWrapper cachingResponse,
                                                String traceId, long startedAt) {
        request.getAsyncContext().addListener(new AsyncListener() {

            @Override
            public void onComplete(AsyncEvent event) {
                logAsyncEnd("완료", null);
            }

            @Override
            public void onTimeout(AsyncEvent event) {
                logAsyncEnd("타임아웃", null);
            }

            @Override
            public void onError(AsyncEvent event) {
                logAsyncEnd("오류", event.getThrowable());
            }

            @Override
            public void onStartAsync(AsyncEvent event) {
                // 재시작은 흐름상 의미가 없어 남기지 않는다.
            }

            private void logAsyncEnd(String result, Throwable throwable) {
                MDC.put(TRACE_ID, traceId);
                try {
                    if (cachingResponse != null) {
                        cachingResponse.copyBodyToResponse();
                    }
                    String message = RESPONSE_ARROW + request.getMethod() + ' ' + request.getRequestURI()
                            + "  " + statusText(response.getStatus())
                            + "  비동기 " + result + " (" + elapsedMillis(startedAt) + "ms)";
                    if (throwable != null) {
                        log.warn("{}  원인: {}", message, throwable.toString());
                    } else {
                        log.info(message);
                    }
                } catch (IOException e) {
                    log.warn("비동기 응답 본문을 되돌려주지 못했습니다. uri={}", request.getRequestURI(), e);
                } finally {
                    MDC.remove(TRACE_ID);
                }
            }
        });
    }

    private void appendBody(StringBuilder message, byte[] body, String contentType, Charset charset) {
        if (!properties.includeBody() || body == null || body.length == 0) {
            return;
        }
        String summary = LogPayloads.summarize(body, contentType, charset, properties.maxBodyLength());
        if (summary != null) {
            message.append(BODY_INDENT).append(summary);
        }
    }

    private boolean shouldCacheRequestBody(HttpServletRequest request) {
        if (!properties.includeBody()) {
            return false;
        }
        String contentType = request.getContentType();
        if (contentType != null) {
            String lowerCase = contentType.toLowerCase();
            // 파일 업로드는 메모리에 통째로 올릴 수 없고,
            // 폼 본문은 미리 읽어버리면 컨테이너가 파라미터를 파싱하지 못해 빈 값이 넘어간다.
            if (lowerCase.startsWith("multipart/")
                    || lowerCase.startsWith(MediaType.APPLICATION_FORM_URLENCODED_VALUE)) {
                return false;
            }
        }
        return request.getContentLengthLong() <= MAX_CACHEABLE_BODY_BYTES;
    }

    /**
     * 응답 본문을 로그에 남기려고 감쌀지.
     *
     * <p>SSE 응답은 감싸면 안 된다. {@code ContentCachingResponseWrapper}는 써 나가는 바이트를
     * 캐시에 모아두었다가 {@code copyBodyToResponse}에서야 내보내므로, 이벤트가 흘러가지 않고
     * 갇힌다. 구독은 필터를 빠져나간 뒤에도 계속 살아 있어, 갇힌 이벤트가 엉뚱한 시점에 한꺼번에
     * 나가거나 아예 도달하지 못한다.
     *
     * <p>Accept 헤더만으로는 가릴 수 없다. 그 값은 클라이언트와 중간 프록시가 정하는 것이라
     * 비어 있거나 {@code *}로 바뀐 채 도착할 수 있다. 우리가 아는 구독 경로는 헤더와 무관하게
     * 비켜간다.
     */
    private boolean shouldCacheResponseBody(HttpServletRequest request) {
        if (!properties.includeBody()) {
            return false;
        }

        if (request.getRequestURI().startsWith(SSE_PATH_PREFIX)) {
            return false;
        }

        String accept = request.getHeader(HttpHeaders.ACCEPT);
        return accept == null || !accept.toLowerCase().contains(MediaType.TEXT_EVENT_STREAM_VALUE);
    }

    private static String requestUri(HttpServletRequest request) {
        String query = request.getQueryString();
        return query == null ? request.getRequestURI() : request.getRequestURI() + '?' + LogPayloads.mask(query);
    }

    private static String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return request.getRemoteAddr();
        }
        // 프록시를 여러 번 거치면 쉼표로 이어 붙는다. 맨 앞이 최초 클라이언트다.
        int separator = forwardedFor.indexOf(',');
        return separator < 0 ? forwardedFor.trim() : forwardedFor.substring(0, separator).trim();
    }

    private static String statusText(int status) {
        HttpStatus resolved = HttpStatus.resolve(status);
        return resolved == null ? String.valueOf(status) : status + " " + resolved.name();
    }

    private static Charset charsetOf(String encoding) {
        if (encoding == null) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(encoding);
        } catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }
}
