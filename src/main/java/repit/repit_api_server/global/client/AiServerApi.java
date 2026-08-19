package repit.repit_api_server.global.client;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;
import repit.repit_api_server.domain.metadata.dto.request.GenerateRequest;
import repit.repit_api_server.domain.metadata.dto.request.MetaDataRequest;
import repit.repit_api_server.domain.metadata.dto.response.GenerateResponse;
import repit.repit_api_server.domain.metadata.dto.response.MetaDataResponse;
import repit.repit_api_server.domain.userdata.feedback.dto.request.FeedbackSoloRequest;
import repit.repit_api_server.domain.userdata.feedback.dto.response.FeedbackAcceptedResponse;
import repit.repit_api_server.domain.userdata.question.dto.request.QuestionTailorRequest;
import repit.repit_api_server.domain.userdata.question.dto.response.QuestionResponse;
import repit.repit_api_server.domain.userdata.question.dto.response.QuestionTailorAcceptedResponse;
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

    // 비동기 채점. 202로 접수만 되고 결과는 callbackUrl로 POST된다.
    @PostExchange("/feedback/solo")
    FeedbackAcceptedResponse requestSoloFeedback(@RequestBody FeedbackSoloRequest request);

    // 면접 시작 직전 원질문 재작성. 마찬가지로 202 접수 후 결과는 콜백으로 온다.
    @PostExchange("/questions/tailor")
    QuestionTailorAcceptedResponse tailorQuestions(@RequestBody QuestionTailorRequest request);
}
