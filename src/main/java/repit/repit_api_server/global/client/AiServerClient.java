package repit.repit_api_server.global.client;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import repit.repit_api_server.domain.metadata.dto.request.GenerateRequest;
import repit.repit_api_server.domain.metadata.dto.request.MetaDataRequest;
import repit.repit_api_server.domain.metadata.dto.response.GenerateResponse;
import repit.repit_api_server.domain.metadata.dto.response.MetaDataResponse;
import repit.repit_api_server.domain.userdata.question.dto.response.QuestionResponse;
import repit.repit_api_server.global.common.ApiResponse;

@Component
@RequiredArgsConstructor
public class AiServerClient {

    private final AiServerApi aiServerApi;

    public MetaDataResponse sendMetaData(String authorization, MetaDataRequest request) {
        return aiServerApi.createMetaData(authorization, request);
    }

    public QuestionResponse createQuestion() {
        ApiResponse<QuestionResponse> response = aiServerApi.createQuestion();

        if (response == null) {
            return null;
        }
        return response.getData();
    }

    public GenerateResponse generate(GenerateRequest request) {
        return aiServerApi.generate(request);
    }

    public GenerateResponse generateMock(GenerateRequest request) {
        return aiServerApi.generateMock(request);
    }
}
