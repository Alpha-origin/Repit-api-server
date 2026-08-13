package repit.repit_api_server.global.client;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.PostExchange;
import repit.repit_api_server.domain.userdata.interview.dto.request.SendUserDataRequest;

public interface ChatServerApi {

    @PostExchange("/api/v1/interview/UserData")
    void sendUserData(@RequestHeader("Authorization") String authorization,
                      @RequestBody SendUserDataRequest request);
}
