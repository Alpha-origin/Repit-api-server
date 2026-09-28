package repit.repit_api_server.domain.userdata.analysis.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import repit.repit_api_server.domain.userdata.analysis.dto.request.AudioAnalysisCallbackRequest;

/**
 * 분석 서버 GET /analysis/audio/jobs/{jobId} 응답.
 *
 * <p>콜백 재전송이 모두 실패해도 결과는 분석 서버에 남는다. {@code result}는 콜백 본문과 같은 내용이라,
 * 콜백을 끝내 받지 못한 요청은 이것으로 결과를 가져온다. 아직 끝나지 않았으면 비어 있다.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class AudioAnalysisJobResponse {
    private String jobId;
    private String status;
    private AudioAnalysisCallbackRequest result;
}
