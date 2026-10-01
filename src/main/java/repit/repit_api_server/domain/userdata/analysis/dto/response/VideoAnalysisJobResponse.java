package repit.repit_api_server.domain.userdata.analysis.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 분석 서버 GET /analysis/video/jobs/{jobId} 응답.
 *
 * <ul>
 *   <li>processing — 대기·처리 중. result·error 모두 비어 있다.</li>
 *   <li>completed — result가 콜백 본문과 같다. 콜백과 같은 검증을 거쳐 저장한다.</li>
 *   <li>failed — 결과를 만들지 못했다. error에 {code, message, retryable}이 온다.</li>
 * </ul>
 * 없는 작업은 404, 보관 기간이 지나 지운 작업은 410으로 온다.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class VideoAnalysisJobResponse {
    private String jobId;
    private String status;
    private Map<String, Object> result;
    private Map<String, Object> error;
}
