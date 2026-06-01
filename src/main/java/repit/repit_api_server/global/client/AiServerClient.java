package repit.repit_api_server.global.client;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import repit.repit_api_server.domain.metadata.dto.request.MetaDataRequest;
import repit.repit_api_server.domain.metadata.dto.response.MetaDataResponse;
import repit.repit_api_server.domain.userdata.question.dto.request.QuestionRequest;
import repit.repit_api_server.domain.userdata.question.dto.response.QuestionResponse;
import repit.repit_api_server.global.common.ApiResponse;

@Component
@RequiredArgsConstructor
public class AiServerClient {
    private final RestClient.Builder restClientBuilder;

    @Value("${ai-server.base-url}")
    private String aiServerBaseUrl;

    // AI 서버에 metaData 전달
    // 테스트 후 수정 필요 가능성 있음
    public MetaDataResponse sendMetaData(String authorization, MetaDataRequest request) {
        return restClientBuilder
                .baseUrl(aiServerBaseUrl)
                .build()
                .post()
                .uri("/api/v1/ai/saveMetaData")
                .header("Authorization", authorization)
                .body(request)
                .retrieve()
                .body(MetaDataResponse.class);
    }

    public QuestionResponse createQuestion(String authorization) {
        ApiResponse<QuestionResponse> response = restClientBuilder
                .baseUrl(aiServerBaseUrl)
                .build()
                .get()
                .uri("/api/vi/ai/createQuestion")
                .header("Authorization", authorization)
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<QuestionResponse>> () {});

        return response.getData();
    }
}
