package repit.repit_api_server.global.client;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;
import repit.repit_api_server.domain.userdata.interview.dto.request.ChatInterviewPrepareRequest;
import repit.repit_api_server.domain.userdata.interview.dto.response.ChatInterviewAllResponse;
import repit.repit_api_server.domain.userdata.interview.dto.response.ChatInterviewResponse;

public interface ChatServerApi {

    @PostExchange("/chat/interviews")
    ChatInterviewResponse prepareInterview(@RequestHeader("Authorization") String authorization,
                                           @RequestBody ChatInterviewPrepareRequest request);

    @GetExchange("/chat/interviews/{sessionId}")
    ChatInterviewAllResponse getInterview(@PathVariable String sessionId);
}
