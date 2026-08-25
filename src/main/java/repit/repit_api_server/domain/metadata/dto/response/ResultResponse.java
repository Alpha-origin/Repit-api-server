package repit.repit_api_server.domain.metadata.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import repit.repit_api_server.domain.metadata.dto.request.CallbackSuccessRequest;

/**
 * 저장된 분석 결과 조회 응답.
 *
 * <p>result만 돌려주면 아직 분석 중인 작업과 실패한 작업이 똑같이 {@code result: null}로 보인다.
 * 채팅 서버는 이 응답 하나로 판단해야 하므로 상태와 실패 사유를 함께 싣는다.
 */
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ResultResponse {
    private String jobId;

    // "pending", "succeeded", "failed"
    private String status;

    // 성공한 분석에만 채워진다.
    private Object result;

    // 실패한 분석에만 채워진다.
    private CallbackSuccessRequest.Error error;
}
