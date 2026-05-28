package repit.repit_api_server.global.client;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import repit.repit_api_server.domain.metadata.dto.request.MetaDataRequest;
import repit.repit_api_server.domain.metadata.dto.response.ApiResponse;
import repit.repit_api_server.domain.metadata.dto.response.MetaDataResponse;

@Component
@RequiredArgsConstructor
public class AuthServerClient {

    private final RestClient.Builder restClientBuilder;

    @Value("${auth-server.base-url}")
    private String authServerBaseUrl;

    public void saveMetaData(String authorization, MetaDataRequest request) {
        restClientBuilder
                .baseUrl(authServerBaseUrl)
                .build()
                .post()
                .uri("/api/v1/auth/saveMetaData")
                .header("Authorization", authorization)
                .body(request)
                .retrieve()
                .toBodilessEntity();
    }



    public MetaDataResponse getMetaData(String authorization) {
        ApiResponse<MetaDataResponse> response =
                restClientBuilder
                        .baseUrl(authServerBaseUrl)
                        .build()
                        .get()
                        .uri("/api/v1/auth/getMetaData")
                        .header("Authorization", authorization)
                        .retrieve()
                        .body(new ParameterizedTypeReference<ApiResponse<MetaDataResponse>>() {});

        return response.getData();
    }
}
