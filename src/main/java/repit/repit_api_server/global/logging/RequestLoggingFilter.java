package repit.repit_api_server.global.logging;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
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
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;

/**
 * 들어오는 요청 한 건을 진입과 종료 두 줄로 남긴다.
 * 요청마다 추적 id를 발급해 MDC에 넣어두므로, 그 요청이 남긴 모든 로그(외부 서버 호출 포함)를
 * 같은 id로 묶어 흐름 그대로 읽을 수 있다.
 */
// 체인의 맨 앞에 세운다. 뒤로 밀면 그 사이 필터에서 걸린 요청이 로그에 아예 남지 않는다.
// 특히 CorsFilter는 허용하지 않은 오리진의 요청을 체인에 넘기지 않고 그 자리에서 403으로 끊는다.
// 뒤에 서 있으면 프론트 연동이 막힌 바로 그 순간의 요청만 로그에서 통째로 사라진다.
// 대신 브라우저의 사전 요청(OPTIONS)은 통과한 경우 남기지 않아 흐름에 섞이지 않게 한다.
@Order(Ordered.HIGHEST_PRECEDENCE)
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {

    public static final String TRACE_ID = "traceId";

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    private static final String REQUEST_ARROW = "--> ";
    private static final String RESPONSE_ARROW = "<-- ";
    private static final String BODY_INDENT = "\n      body: ";
    private static final String FORM_INDENT = "\n      form: ";

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


        // 사전 요청은 브라우저가 본 요청마다 한 번씩 더 보내는 것이라, 통과한 것까지 남기면
        // 흐름이 두 배로 늘어난다. 남길지는 결과를 보고 정하므로 진입 줄은 잡아둔다.
        boolean preflight = CorsUtils.isPreFlightRequest(request);

        ContentCachingResponseWrapper cachingResponse =
                !preflight && shouldCacheResponseBody(request) ? new ContentCachingResponseWrapper(response) : null;
        HttpServletResponse responseToUse = cachingResponse != null ? cachingResponse : response;

        if (!preflight) {
            logRequest(request, requestBody);
        }

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

            if (preflight) {
                logPreflight(request, responseToUse.getStatus(), elapsedMillis);
            } else if (request.isAsyncStarted()) {
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
        appendFormSummary(message, request, contentType);
        log.info(message.toString());
    }

    /**
     * 사전 요청은 막혔을 때만 남긴다.
     *
     * <p>통과한 사전 요청은 곧바로 뒤따르는 본 요청과 내용이 겹쳐 흐름만 늘린다. 반대로 막힌
     * 사전 요청은 본 요청이 아예 나가지 못하므로, 남기지 않으면 서버 쪽에는 아무 흔적도 없이
     * 브라우저에만 CORS 오류가 뜬다. 어느 오리진이 무엇을 요구했는지 함께 남겨야 허용 목록을
     * 어떻게 고칠지 판단할 수 있다.
     */
    private void logPreflight(HttpServletRequest request, int status, long elapsedMillis) {
        if (status < 400) {
            return;
        }
        StringBuilder message = new StringBuilder(RESPONSE_ARROW)
                .append("OPTIONS ").append(request.getRequestURI())
                .append("  ").append(statusText(status))
                .append("  사전 요청 거절 (").append(elapsedMillis).append("ms)")
                .append("  origin=").append(request.getHeader(HttpHeaders.ORIGIN));

        String requestMethod = request.getHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD);
        if (requestMethod != null) {
            message.append("  요청한 메서드=").append(requestMethod);
        }
        String requestHeaders = request.getHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS);
        if (requestHeaders != null) {
            message.append("  요청한 헤더=").append(requestHeaders);
        }
        log.warn(message.toString());
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
                    if (throwable == null) {
                        log.info(message);
                    } else if (ClientDisconnect.isClientGone(throwable)) {
                        // 구독자가 떠나서 끝난 것이다. SSE 구독은 몇 분씩 열려 있어 이렇게 끝나는
                        // 편이 오히려 흔하다. 실패로 남기면 손볼 것 없는 줄이 로그를 메운다.
                        log.info("{}  이유: {}", message, ClientDisconnect.rootCauseMessage(throwable));
                    } else {
                        log.warn("{}  원인: {}", message, throwable.toString());
                    }
                } catch (IOException e) {
                    log.warn("비동기 응답 본문을 되돌려주지 못했습니다. uri={}", request.getRequestURI(), e);
                } finally {
                    MDC.remove(TRACE_ID);
                }
            }
        });
    }

    /**
     * 파일 업로드와 폼 본문은 본문 로그에서 빠지므로, 무엇이 올라왔는지만이라도 남긴다.
     *
     * <p>업로드 본문은 메모리에 통째로 올릴 수 없고, 폼 본문은 우리가 미리 읽으면 컨테이너가
     * 파라미터를 파싱하지 못한다. 그래서 {@link #shouldCacheRequestBody}가 둘 다 비켜가는데,
     * 그 결과 이 경로들만 요청 줄만 남고 무엇을 보냈는지는 로그에 흔적이 없다.
     * {@code POST /api/v1/metaData/dataUpload}가 그렇다.
     *
     * <p>본문을 우리가 읽는 대신, 컨테이너가 이미 파싱해 둔 파라미터와 파트 목록을 빌려 온다.
     * 파싱은 컨테이너가 한 번만 하고 결과를 보관하므로 뒤이어 컨트롤러가 받는 값은 그대로다.
     * 파일 내용은 남기지 않고 이름과 크기만 남긴다.
     */
    private void appendFormSummary(StringBuilder message, HttpServletRequest request, String contentType) {
        if (!properties.includeBody() || contentType == null) {
            return;
        }
        String lowerCase = contentType.toLowerCase();
        boolean multipart = lowerCase.startsWith("multipart/");
        if (!multipart && !lowerCase.startsWith(MediaType.APPLICATION_FORM_URLENCODED_VALUE)) {
            return;
        }

        StringJoiner summary = new StringJoiner(", ");
        appendParameters(summary, request);
        if (multipart) {
            appendUploadedFiles(summary, request);
        }
        if (summary.length() == 0) {
            return;
        }

        String text = summary.toString();
        int maxLength = properties.maxBodyLength();
        message.append(FORM_INDENT)
                .append(text.length() <= maxLength ? text : text.substring(0, maxLength) + "...");
    }

    private void appendParameters(StringJoiner summary, HttpServletRequest request) {
        try {
            for (Map.Entry<String, String[]> parameter : request.getParameterMap().entrySet()) {
                summary.add(LogPayloads.mask(parameter.getKey() + "=" + String.join("|", parameter.getValue())));
            }
        } catch (RuntimeException e) {
            // 파싱에 실패한 요청이다. 여기서 대신 답하지 않는다. 같은 실패를 컨트롤러가 다시 마주쳐
            // 원래대로 응답으로 이어지고, 그 응답은 아래 응답 줄에 상태 코드로 남는다.
        }
    }

    private void appendUploadedFiles(StringJoiner summary, HttpServletRequest request) {
        try {
            Collection<Part> parts = request.getParts();
            for (Part part : parts) {
                String fileName = part.getSubmittedFileName();
                if (fileName != null) {
                    // 파일 내용은 남길 수 없다. 어떤 파일이 얼마나 올라왔는지만 남긴다.
                    summary.add(part.getName() + "=" + fileName
                            + "(" + LogPayloads.humanReadableSize(part.getSize()) + ")");
                }
            }
        } catch (IOException | ServletException | RuntimeException e) {
            // 위와 같다. 로그를 남기려다 요청을 망치지 않는다.
        }
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
            // 대신 컨테이너가 파싱해 둔 것을 빌려 appendFormSummary가 요약을 남긴다.
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
