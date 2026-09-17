package repit.repit_api_server.domain.userdata.recording.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import repit.repit_api_server.domain.userdata.question.entity.enums.Type;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 면접 녹화 분석 요청. 한 면접의 질문·답변과 답변 영상을 한 번에 넘긴다.
 *
 * <p>분석 서버의 다른 작업과 같은 규약을 따른다 — 202로 접수만 되고 결과는 callbackUrl로 온다.
 * id는 채점 요청과 마찬가지로 문자열로 보낸다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecordingAnalysisRequest {
    private String sessionId;
    private String interviewId;
    private String userId;
    // SOLO | MULTI
    private String mode;
    private String callbackUrl;
    private List<Question> questions;
    private List<Answer> answers;
    private List<Recording> recordings;

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Question {
        private String questionId;
        // FOLLOW만 값이 있다.
        private String parentId;
        private Type type;
        private String intention;
        private String content;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Answer {
        private String answerId;
        private String questionId;
        private String content;
        // 초. 비어 올 수 있다.
        private Integer responseTime;
        // ISO 8601 + UTC 오프셋(Z)으로 직렬화된다.
        private OffsetDateTime createdAt;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Recording {
        private String recordingId;
        // 위 questions의 questionId. 웹이 질문 번호를 보내지 않았거나 기록에 없는 질문이면 비어 있다.
        private String questionId;
        // 서명된 S3 주소. 만료가 있으니 접수 뒤 오래 두지 말고 내려받는다.
        private String videoUrl;
        private String contentType;
        private Long fileSize;
        // ISO 8601 + UTC 오프셋(Z)으로 직렬화된다.
        private OffsetDateTime uploadedAt;
    }
}
