package repit.repit_api_server.global.client;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import repit.repit_api_server.domain.userdata.interview.dto.request.SendUserDataRequest;

@Component
@RequiredArgsConstructor
public class ChatServerClient {

    private final RestClient.Builder restClientBuilder;

    @Value("${chat-server.base-url}")
    private String chatServerBaseUrl;

    public void sendUserData(String authorization, SendUserDataRequest request) {
        restClientBuilder
                .baseUrl(chatServerBaseUrl)
                .build()
                .post()
                .uri("/api/v1/interview/UserData")
                .header("Authorization", authorization)
                .body(request)
                .retrieve()
                .toBodilessEntity();
    }
}
