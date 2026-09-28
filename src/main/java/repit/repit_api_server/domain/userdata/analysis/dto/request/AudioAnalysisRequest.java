package repit.repit_api_server.domain.userdata.analysis.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 분석 서버 POST /analysis/audio 요청 본문.
 *
 * <p>접수는 202로 받고 결과는 callbackUrl로 온다. id는 모두 문자열로 보낸다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AudioAnalysisRequest {
    // 이 서버가 만든 요청 id. 콜백이 접수 응답보다 먼저 와도 이 값으로 되짚는다.
    private String requestId;
    private String sessionId;
    private String interviewId;
    private String userId;
    private String callbackUrl;
    // 업로드가 끝난 답변 녹음. 1~12개.
    private List<Recording> recordings;

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Recording {
        private String recordingId;
        // 우리 질문 PK. 채팅 서버 질문 번호가 아니다.
        private String questionId;
        private String answerId;
        // 서명된 S3 GET 주소. 만료가 있다.
        private String fileUrl;
        // 파일 앞 바이트로 확인한 실제 형식.
        private String contentType;
        private Long fileSize;
        // ISO 8601 + UTC 오프셋(Z)으로 직렬화된다.
        private OffsetDateTime uploadedAt;
        // user / timeout / interrupted / unknown. 웹이 보내지 않았으면 unknown.
        private String endReason;
    }
}
