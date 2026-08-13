package repit.repit_api_server.global.client;

import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import repit.repit_api_server.global.exception.ExternalApiException;

import java.util.function.Function;
import java.util.function.Supplier;

@Component
public class ExternalApiExecutor {

    private static final int MAX_ATTEMPTS = 3;
    private static final long BASE_BACKOFF_MILLIS = 200L;

    /**
     * @param idempotent 몇 번 실행되든 결과가 같은 호출(GET 등)이면 true.
     *                   생성/전송처럼 부작용이 있는 호출(POST 등)은 false로 넘겨 재시도로 인한 중복 실행을 막는다.
     */
    public <T> T execute(String serverName, Supplier<T> call, Function<HttpStatusCode, String> messageResolver, boolean idempotent) {
        int maxAttempts = idempotent ? MAX_ATTEMPTS : 1;
        ExternalApiException lastFailure = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return call.get();
            } catch (RestClientResponseException e) {
                // 4xx는 재시도해도 결과가 같으므로 즉시 실패 처리
                if (!e.getStatusCode().is5xxServerError()) {
                    throw new ExternalApiException(messageResolver.apply(e.getStatusCode()), e.getStatusCode(), e);
                }
                lastFailure = new ExternalApiException(messageResolver.apply(e.getStatusCode()), e.getStatusCode(), e);
            } catch (ResourceAccessException e) {
                lastFailure = new ExternalApiException(serverName + " 서버와 연결할 수 없습니다. 잠시 후 다시 시도해주세요.", null, e);
            }

            if (attempt < maxAttempts) {
                sleep(BASE_BACKOFF_MILLIS * (1L << (attempt - 1)));
            }
        }

        throw lastFailure;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("재시도 대기 중 인터럽트가 발생했습니다.", e);
        }
    }
}
