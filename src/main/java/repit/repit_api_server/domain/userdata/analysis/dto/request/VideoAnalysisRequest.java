package repit.repit_api_server.domain.userdata.analysis.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 분석 서버 POST /analysis/video 요청 본문.
 *
 * <p>계약에 필드 모양이 아직 없어 음성 분석 요청을 따라 정했다. 분석 서버 명세가 오면 이 클래스만 맞추면 된다 —
 * 보낸 본문은 그대로 저장해 두고 다시 보낼 때 그것을 싣는다. id는 모두 문자열로 보낸다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoAnalysisRequest {
    // 이 서버가 만든 요청 id. 같은 sessionId·requestId·내용이면 분석 서버가 기존 작업을 돌려준다.
    private String requestId;
    private String sessionId;
    private String interviewId;
    private String userId;
    private String callbackUrl;
    // 업로드가 끝난 면접 화면 전체 녹화 하나.
    private Video video;

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Video {
        // interview_recording.recording_id.
        private String videoId;
        // 서명된 S3 GET 주소. 서명 파라미터는 분석 서버의 중복 판단에서 빠진다.
        private String fileUrl;
        // 파일 앞 바이트로 확인한 실제 형식. video/mp4 또는 video/webm.
        private String contentType;
        private Long fileSize;
        // ISO 8601 + UTC 오프셋(Z)으로 직렬화된다.
        private OffsetDateTime uploadedAt;
    }
}
