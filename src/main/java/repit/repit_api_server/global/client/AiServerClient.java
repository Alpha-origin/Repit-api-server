package repit.repit_api_server.global.client;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import repit.repit_api_server.domain.metadata.dto.request.MetaDataRequest;

@Component
@RequiredArgsConstructor
public class AiServerClient {
    private final RestClient.Builder restClientBuilder;

    @Value("${ai-server.base-url}")
    private String aiServerBaseUrl;

    public void sendMetaData(String authorization, MetaDataRequest request) {
        restClientBuilder
                .baseUrl(aiServerBaseUrl)
                .build()
                .post()
                .uri("/api/v1/ai/saveMetaData")
                .header("Authorization", authorization)
                .body(request)
                .retrieve()
                .toBodilessEntity();
    }
}
