package repit.repit_api_server.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import repit.repit_api_server.global.client.AiServerApi;
import repit.repit_api_server.global.client.AiServerClient;
import repit.repit_api_server.global.client.AuthServerApi;
import repit.repit_api_server.global.client.AuthServerClient;
import repit.repit_api_server.global.client.ChatServerApi;
import repit.repit_api_server.global.client.ChatServerClient;
import repit.repit_api_server.global.logging.ExternalApiLoggingInterceptor;
import repit.repit_api_server.global.logging.HttpLoggingProperties;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(HttpLoggingProperties.class)
public class HttpInterfaceConfig {

    private final HttpLoggingProperties httpLoggingProperties;

    public HttpInterfaceConfig(HttpLoggingProperties httpLoggingProperties) {
        this.httpLoggingProperties = httpLoggingProperties;
    }

    @Bean
    public AiServerApi aiServerApi(RestClient.Builder restClientBuilder,
                                    @Value("${ai-server.base-url}") String aiServerBaseUrl,
                                    @Value("${external-api.connect-timeout:3s}") String connectTimeout,
                                    @Value("${external-api.read-timeout:5s}") String readTimeout) {
        return buildClient(restClientBuilder, AiServerClient.SERVER_NAME, aiServerBaseUrl, connectTimeout, readTimeout, AiServerApi.class);
    }

    @Bean
    public AuthServerApi authServerApi(RestClient.Builder restClientBuilder,
                                        @Value("${auth-server.base-url}") String authServerBaseUrl,
                                        @Value("${external-api.connect-timeout:3s}") String connectTimeout,
                                        @Value("${external-api.read-timeout:5s}") String readTimeout) {
        return buildClient(restClientBuilder, AuthServerClient.SERVER_NAME, authServerBaseUrl, connectTimeout, readTimeout, AuthServerApi.class);
    }

    @Bean
    public ChatServerApi chatServerApi(RestClient.Builder restClientBuilder,
                                        @Value("${chat-server.base-url}") String chatServerBaseUrl,
                                        @Value("${external-api.connect-timeout:3s}") String connectTimeout,
                                        @Value("${external-api.read-timeout:5s}") String readTimeout) {
        return buildClient(restClientBuilder, ChatServerClient.SERVER_NAME, chatServerBaseUrl, connectTimeout, readTimeout, ChatServerApi.class);
    }

    private <T> T buildClient(RestClient.Builder restClientBuilder, String serverName, String baseUrl,
                               String connectTimeout, String readTimeout, Class<T> httpInterfaceType) {
        Duration connectDuration = DurationStyle.detectAndParse(connectTimeout);
        Duration readDuration = DurationStyle.detectAndParse(readTimeout);

        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.detect()
                .build(HttpClientSettings.defaults().withTimeouts(connectDuration, readDuration));

        if (httpLoggingProperties.enabled() && httpLoggingProperties.includeBody()) {
            // 로그가 응답 본문을 먼저 읽어도 원래 소비자가 같은 본문을 다시 읽을 수 있게 버퍼링한다.
            requestFactory = new BufferingClientHttpRequestFactory(requestFactory);
        }

        restClientBuilder = restClientBuilder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory);

        if (httpLoggingProperties.enabled()) {
            restClientBuilder = restClientBuilder
                    .requestInterceptor(new ExternalApiLoggingInterceptor(serverName, httpLoggingProperties));
        }

        RestClient restClient = restClientBuilder.build();
        RestClientAdapter adapter = RestClientAdapter.create(restClient);
        HttpServiceProxyFactory factory = HttpServiceProxyFactory.builderFor(adapter).build();
        return factory.createClient(httpInterfaceType);
    }
}
