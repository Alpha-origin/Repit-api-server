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

    public <T> T execute(String serverName, Supplier<T> call, Function<HttpStatusCode, String> messageResolver) {
        try {
            return call.get();
        } catch (RestClientResponseException e) {
            throw new ExternalApiException(messageResolver.apply(e.getStatusCode()), e.getStatusCode(), e);
        } catch (ResourceAccessException e) {
            throw new ExternalApiException(serverName + " 서버와 연결할 수 없습니다. 잠시 후 다시 시도해주세요.", null, e);
        }
    }
}
