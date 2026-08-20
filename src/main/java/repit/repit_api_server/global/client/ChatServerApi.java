package repit.repit_api_server.global.client;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;
import repit.repit_api_server.domain.userdata.interview.dto.request.ChatInterviewPrepareRequest;
import repit.repit_api_server.domain.userdata.interview.dto.response.ChatInterviewAllResponse;
import repit.repit_api_server.domain.userdata.interview.dto.response.ChatInterviewResponse;

public interface ChatServerApi {

    // 분석 서버 콜백에서 호출되는 서버 간 전달이라 사용자 토큰이 없다. 신원은 본문의 userId로 넘긴다.
    @PostExchange("/chat/interviews")
    ChatInterviewResponse prepareInterview(@RequestBody ChatInterviewPrepareRequest request);

    @GetExchange("/chat/interviews/{sessionId}")
    ChatInterviewAllResponse getInterview(@PathVariable String sessionId);
}
