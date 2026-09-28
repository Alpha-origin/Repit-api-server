package repit.repit_api_server.domain.userdata.analysis.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 분석 서버가 음성 분석을 접수하며 돌려주는 응답. */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class AudioAnalysisAcceptedResponse {
    private String jobId;
    private String requestId;
    private String sessionId;
    // "accepted"
    private String status;
}
