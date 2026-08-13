package repit.repit_api_server.global.client;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatusCode;
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

    private static final String SERVER_NAME = "AI";

    private final AiServerApi aiServerApi;
    private final ExternalApiExecutor executor;

    public MetaDataResponse sendMetaData(String authorization, MetaDataRequest request) {
        return executor.execute(SERVER_NAME,
                () -> aiServerApi.createMetaData(authorization, request),
                this::resolveMessage, false);
    }

    public QuestionResponse createQuestion() {
        ApiResponse<QuestionResponse> response = executor.execute(SERVER_NAME,
                aiServerApi::createQuestion,
                this::resolveMessage, true);

        return response == null ? null : response.getData();
    }

    public GenerateResponse generate(GenerateRequest request) {
        return executor.execute(SERVER_NAME,
                () -> aiServerApi.generate(request),
                this::resolveMessage, false);
    }

    public GenerateResponse generateMock(GenerateRequest request) {
        return executor.execute(SERVER_NAME,
                () -> aiServerApi.generateMock(request),
                this::resolveMessage, false);
    }

    private String resolveMessage(HttpStatusCode status) {
        if (status.value() == 401) {
            return "AI 서버 인증에 실패했습니다.";
        }
        if (status.value() == 404) {
            return "요청한 AI 리소스를 찾을 수 없습니다.";
        }
        if (status.is5xxServerError()) {
            return "AI 응답 생성 중 오류가 발생했습니다.";
        }
        return "AI 서버 요청이 올바르지 않습니다. (" + status.value() + ")";
    }
}
