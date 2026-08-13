package repit.repit_api_server.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import repit.repit_api_server.global.client.AiServerApi;
import repit.repit_api_server.global.client.AuthServerApi;
import repit.repit_api_server.global.client.ChatServerApi;

import java.time.Duration;

@Configuration
public class HttpInterfaceConfig {

    @Bean
    public AiServerApi aiServerApi(RestClient.Builder restClientBuilder,
                                    @Value("${ai-server.base-url}") String aiServerBaseUrl,
                                    @Value("${external-api.connect-timeout:3s}") String connectTimeout,
                                    @Value("${external-api.read-timeout:5s}") String readTimeout) {
        return buildClient(restClientBuilder, aiServerBaseUrl, connectTimeout, readTimeout, AiServerApi.class);
    }

    @Bean
    public AuthServerApi authServerApi(RestClient.Builder restClientBuilder,
                                        @Value("${auth-server.base-url}") String authServerBaseUrl,
                                        @Value("${external-api.connect-timeout:3s}") String connectTimeout,
                                        @Value("${external-api.read-timeout:5s}") String readTimeout) {
        return buildClient(restClientBuilder, authServerBaseUrl, connectTimeout, readTimeout, AuthServerApi.class);
    }

    @Bean
    public ChatServerApi chatServerApi(RestClient.Builder restClientBuilder,
                                        @Value("${chat-server.base-url}") String chatServerBaseUrl,
                                        @Value("${external-api.connect-timeout:3s}") String connectTimeout,
                                        @Value("${external-api.read-timeout:5s}") String readTimeout) {
        return buildClient(restClientBuilder, chatServerBaseUrl, connectTimeout, readTimeout, ChatServerApi.class);
    }

    private <T> T buildClient(RestClient.Builder restClientBuilder, String baseUrl,
                               String connectTimeout, String readTimeout, Class<T> httpInterfaceType) {
        Duration connectDuration = DurationStyle.detectAndParse(connectTimeout);
        Duration readDuration = DurationStyle.detectAndParse(readTimeout);

        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.detect()
                .build(HttpClientSettings.defaults().withTimeouts(connectDuration, readDuration));

        RestClient restClient = restClientBuilder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
        RestClientAdapter adapter = RestClientAdapter.create(restClient);
        HttpServiceProxyFactory factory = HttpServiceProxyFactory.builderFor(adapter).build();
        return factory.createClient(httpInterfaceType);
    }
}
