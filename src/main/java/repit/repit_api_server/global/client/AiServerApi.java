package repit.repit_api_server.global.client;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;
import repit.repit_api_server.domain.metadata.dto.request.GenerateRequest;
import repit.repit_api_server.domain.metadata.dto.request.MetaDataRequest;
import repit.repit_api_server.domain.metadata.dto.response.GenerateResponse;
import repit.repit_api_server.domain.metadata.dto.response.MetaDataResponse;
import repit.repit_api_server.domain.userdata.question.dto.response.QuestionResponse;
import repit.repit_api_server.global.common.ApiResponse;

public interface AiServerApi {

    @PostExchange("/api/v1/ai/createMetaData")
    MetaDataResponse createMetaData(@RequestHeader("Authorization") String authorization,
                                    @RequestBody MetaDataRequest request);

    @GetExchange("/api/v1/ai/createQuestion")
    ApiResponse<QuestionResponse> createQuestion();

    @PostExchange("/generate")
    GenerateResponse generate(@RequestBody GenerateRequest request);

    @PostExchange("/generate-mock")
    GenerateResponse generateMock(@RequestBody GenerateRequest request);
}
