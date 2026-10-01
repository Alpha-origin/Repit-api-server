package repit.repit_api_server.domain.userdata.analysis.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 분석 서버가 영상 분석을 접수하며 돌려주는 응답. 쓰는 것은 작업 id뿐이다. */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class VideoAnalysisAcceptedResponse {
    private String jobId;
}
