package repit.repit_api_server.domain.userdata.feedback.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 채점에 함께 싣는 답변 영상 하나. 1:1과 N:1 요청이 같은 모양을 쓴다.
 *
 * <p>웹이 음성 답변을 마칠 때마다 올린 MP4다. 텍스트로 답한 질문에는 영상이 없다.
 * 같은 질문에 영상이 여러 번 올라왔으면 가장 나중 것 하나만 싣는다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedbackRecording {
    private String recordingId;
    // 같은 요청 questions의 questionId. 질문 하나에 영상은 하나다.
    private String questionId;
    // 같은 요청 answers의 answerId. 그 질문에 저장된 답변이 없으면 비어 있다.
    private String answerId;
    // 서명된 S3 GET 주소. 만료가 있으니 접수 뒤 오래 두지 말고 내려받는다.
    private String videoUrl;
    private String contentType;
    private Long fileSize;
    // ISO 8601 + UTC 오프셋(Z)으로 직렬화된다.
    private OffsetDateTime uploadedAt;
}
