package repit.repit_api_server.domain.userdata.analysis.dto.request;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 분석 서버가 음성 분석을 마치고 보내는 콜백.
 *
 * <p>status는 전체 결과의 완전성이다(ready / partial / unavailable). results에는 요청한 녹음이 실패한 것까지 모두 실린다.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class AudioAnalysisCallbackRequest {
    private String jobId;
    private String requestId;
    private String sessionId;
    private String interviewId;
    private String userId;
    private String status;
    private List<Result> results;

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Result {
        private String recordingId;
        private String questionId;
        private String answerId;
        private String status;
        // 디코딩된 음성 길이. 확보하지 못하면 null.
        private Long durationMs;
        // {status, data, error}. data 모양은 분석 서버가 정하므로 그대로 받아 둔다.
        private Map<String, Object> timing;
        private Map<String, Object> fluency;
        // 실패 사유. 문자열로 올지 객체로 올지 정해져 있지 않아 무엇이든 받는다.
        private Object error;
    }
}
