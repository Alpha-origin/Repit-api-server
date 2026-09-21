package repit.repit_api_server.domain.userdata.feedback.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 채점에 함께 싣는 답변 파일 하나. 1:1과 N:1 요청이 같은 모양을 쓴다.
 *
 * <p>웹이 질문에 답할 때마다 올린 그 답변의 음성이다. 텍스트로 답한 질문에는 없다.
 * 같은 질문에 여러 번 올라왔으면 가장 나중 것 하나만 싣는다.
 *
 * <p>면접 화면 전체를 담은 영상은 답변마다 있는 것이 아니라 요청에 하나뿐이라 여기 오지 않는다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedbackRecording {
    private String recordingId;
    // 같은 요청 questions의 questionId. 질문 하나에 파일은 하나다.
    private String questionId;
    // 같은 요청 answers의 answerId. 그 질문에 저장된 답변이 없으면 비어 있다.
    private String answerId;
    // 서명된 S3 GET 주소. 만료가 있으니 접수 뒤 오래 두지 말고 내려받는다.
    private String fileUrl;
    // 실제 형식. 보통 audio/mpeg이지만 브라우저에 따라 audio/webm이나 audio/mp4일 수 있다.
    private String contentType;
    private Long fileSize;
    // ISO 8601 + UTC 오프셋(Z)으로 직렬화된다.
    private OffsetDateTime uploadedAt;
}
