package repit.repit_api_server.domain.userdata.feedback.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 면접 화면을 처음부터 끝까지 담은 영상. 요청에 많아야 하나다.
 *
 * <p>웹이 면접을 멈출 때 한 번 올린다. 질문 하나가 아니라 면접 전체에 걸쳐 있어서 어느 질문에도
 * 붙이지 않는다. 답변별 음성은 recordings에 따로 실린다.
 *
 * <p>텍스트로만 진행했거나 영상 업로드가 제때 끝나지 않은 면접에는 없다. 없어도 채점은 한다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedbackInterviewVideo {
    private String recordingId;
    // 서명된 S3 GET 주소. 만료가 있으니 접수 뒤 오래 두지 말고 내려받는다.
    private String videoUrl;
    private String contentType;
    private Long fileSize;
    // ISO 8601 + UTC 오프셋(Z)으로 직렬화된다.
    private OffsetDateTime uploadedAt;
}
