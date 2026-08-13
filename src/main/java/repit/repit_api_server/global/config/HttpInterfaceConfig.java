package repit.repit_api_server.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import repit.repit_api_server.global.client.AiServerApi;
import repit.repit_api_server.global.client.AuthServerApi;
import repit.repit_api_server.global.client.ChatServerApi;

@Configuration
public class HttpInterfaceConfig {

    @Bean
    public AiServerApi aiServerApi(RestClient.Builder restClientBuilder,
                                    @Value("${ai-server.base-url}") String aiServerBaseUrl) {
        return buildClient(restClientBuilder, aiServerBaseUrl, AiServerApi.class);
    }

    @Bean
    public AuthServerApi authServerApi(RestClient.Builder restClientBuilder,
                                        @Value("${auth-server.base-url}") String authServerBaseUrl) {
        return buildClient(restClientBuilder, authServerBaseUrl, AuthServerApi.class);
    }

    @Bean
    public ChatServerApi chatServerApi(RestClient.Builder restClientBuilder,
                                        @Value("${chat-server.base-url}") String chatServerBaseUrl) {
        return buildClient(restClientBuilder, chatServerBaseUrl, ChatServerApi.class);
    }

    private <T> T buildClient(RestClient.Builder restClientBuilder, String baseUrl, Class<T> httpInterfaceType) {
        RestClient restClient = restClientBuilder.baseUrl(baseUrl).build();
        RestClientAdapter adapter = RestClientAdapter.create(restClient);
        HttpServiceProxyFactory factory = HttpServiceProxyFactory.builderFor(adapter).build();
        return factory.createClient(httpInterfaceType);
    }
}
