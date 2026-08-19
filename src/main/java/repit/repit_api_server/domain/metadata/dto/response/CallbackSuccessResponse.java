package repit.repit_api_server.domain.metadata.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import repit.repit_api_server.domain.metadata.dto.request.CallbackSuccessRequest;

/** SSE로 구독자에게 흘려보내는 분석 결과. 실패면 result 대신 error가 채워진다. */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class CallbackSuccessResponse {
    private String job_id;

    private String status;

    private Object result;

    private CallbackSuccessRequest.Error error;
}
