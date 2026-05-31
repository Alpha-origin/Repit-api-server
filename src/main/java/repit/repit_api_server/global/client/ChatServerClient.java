package repit.repit_api_server.global.client;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
public class ChatServerClient {

    private final RestClient.Builder restClientBuilder;

    @Value("${chat-server.base-url}")
    private String chatServerBaseUrl;

    
}
