package repit.repit_api_server.global.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 우리 서버가 다른 서버로 나가서 무엇을 주고받았는지 남긴다.
 * 들어온 요청과 같은 추적 id 아래 찍히므로, 요청 한 건이 어느 서버를 거쳐 갔는지 순서대로 읽힌다.
 */
public class ExternalApiLoggingInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ExternalApiLoggingInterceptor.class);

    // 들어온 요청 로그보다 한 칸 들여써서 그 요청에서 파생된 호출임을 눈으로 구분한다.
    private static final String REQUEST_ARROW = "  ext --> ";
    private static final String RESPONSE_ARROW = "  ext <-- ";
    private static final String BODY_INDENT = "\n            body: ";

    private final String serverName;
    private final HttpLoggingProperties properties;

    public ExternalApiLoggingInterceptor(String serverName, HttpLoggingProperties properties) {
        this.serverName = serverName;
        this.properties = properties;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {

        long startedAt = System.nanoTime();
        logRequest(request, body);

        try {
            ClientHttpResponse response = execution.execute(request, body);
            logResponse(request, response, elapsedMillis(startedAt));
            return response;
        } catch (IOException | RuntimeException e) {
            // 연결 실패나 타임아웃이라 상태 코드 자체가 없다. 재시도 여부는 ExternalApiExecutor가 이어서 남긴다.
            log.error("{}[{}] {} {}  응답 없음 ({}ms)  원인: {}", RESPONSE_ARROW, serverName,
                    request.getMethod(), request.getURI(), elapsedMillis(startedAt), e.toString());
            throw e;
        }
    }

    private void logRequest(HttpRequest request, byte[] body) {
        StringBuilder message = new StringBuilder(REQUEST_ARROW)
                .append('[').append(serverName).append("] ")
                .append(request.getMethod()).append(' ').append(request.getURI());

        String authorization = LogPayloads.maskAuthorization(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        if (authorization != null) {
            message.append("  auth=").append(authorization);
        }

        appendBody(message, body, request.getHeaders().getContentType());
        log.info(message.toString());
    }

    private void logResponse(HttpRequest request, ClientHttpResponse response, long elapsedMillis) throws IOException {
        int status = response.getStatusCode().value();

        StringBuilder message = new StringBuilder(RESPONSE_ARROW)
                .append('[').append(serverName).append("] ")
                .append(request.getMethod()).append(' ').append(request.getURI())
                .append("  ").append(statusText(status))
                .append("  (").append(elapsedMillis).append("ms)");

        appendBody(message, readBody(response), response.getHeaders().getContentType());

        if (status >= 500) {
            log.error(message.toString());
        } else if (status >= 400) {
            log.warn(message.toString());
        } else {
            log.info(message.toString());
        }
    }

    /**
     * 응답 본문은 원래 소비자도 읽어야 하므로, 버퍼링된 클라이언트에서만 미리 읽는다.
     * 읽는 도중 문제가 생겨도 호출 자체는 살려 보낸다.
     */
    private byte[] readBody(ClientHttpResponse response) {
        if (!properties.includeBody()) {
            return null;
        }
        try {
            return StreamUtils.copyToByteArray(response.getBody());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private void appendBody(StringBuilder message, byte[] body, MediaType contentType) {
        if (!properties.includeBody() || body == null || body.length == 0) {
            return;
        }
        Charset charset = contentType != null && contentType.getCharset() != null
                ? contentType.getCharset()
                : StandardCharsets.UTF_8;
        String summary = LogPayloads.summarize(body, contentType != null ? contentType.toString() : null,
                charset, properties.maxBodyLength());
        if (summary != null) {
            message.append(BODY_INDENT).append(summary);
        }
    }

    private static String statusText(int status) {
        HttpStatus resolved = HttpStatus.resolve(status);
        return resolved == null ? String.valueOf(status) : status + " " + resolved.name();
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }
}
