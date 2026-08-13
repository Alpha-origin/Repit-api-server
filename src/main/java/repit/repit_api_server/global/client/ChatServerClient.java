package repit.repit_api_server.global.client;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import repit.repit_api_server.domain.userdata.interview.dto.request.SendUserDataRequest;

@Component
@RequiredArgsConstructor
public class ChatServerClient {

    private static final String SERVER_NAME = "채팅";

    private final ChatServerApi chatServerApi;
    private final ExternalApiExecutor executor;

    public void sendUserData(String authorization, SendUserDataRequest request) {
        executor.execute(SERVER_NAME,
                () -> {
                    chatServerApi.sendUserData(authorization, request);
                    return null;
                },
                this::resolveMessage);
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
