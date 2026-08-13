package repit.repit_api_server.global.client;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import repit.repit_api_server.domain.userdata.interview.dto.request.SendUserDataRequest;

@Component
@RequiredArgsConstructor
public class ChatServerClient {

    private final ChatServerApi chatServerApi;

    public void sendUserData(String authorization, SendUserDataRequest request) {
        chatServerApi.sendUserData(authorization, request);
    }
}
