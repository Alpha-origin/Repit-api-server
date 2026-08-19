package repit.repit_api_server.global.client;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import repit.repit_api_server.domain.userdata.interview.dto.request.ChatInterviewPrepareRequest;
import repit.repit_api_server.domain.userdata.interview.dto.response.ChatInterviewAllResponse;
import repit.repit_api_server.domain.userdata.interview.dto.response.ChatInterviewResponse;

@Component
@RequiredArgsConstructor
public class ChatServerClient {

    private static final String SERVER_NAME = "채팅";

    private final ChatServerApi chatServerApi;
    private final ExternalApiExecutor executor;

    public ChatInterviewResponse prepareInterview(String authorization, ChatInterviewPrepareRequest request) {
        return executor.execute(SERVER_NAME,
                () -> chatServerApi.prepareInterview(authorization, request),
                this::resolveMessage, false);
    }

    public ChatInterviewAllResponse getInterview(String sessionId) {
        return executor.execute(SERVER_NAME,
                () -> chatServerApi.getInterview(sessionId),
                this::resolveMessage, true);
    }

    private String resolveMessage(HttpStatusCode status) {
        if (status.value() == 401) {
            return "채팅 서버 인증에 실패했습니다.";
        }
        if (status.is5xxServerError()) {
            return "채팅 서버에 오류가 발생했습니다.";
        }
        return "채팅 서버 요청이 올바르지 않습니다. (" + status.value() + ")";
    }
}
