package repit.repit_api_server.domain.userdata.interview.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import repit.repit_api_server.domain.userdata.question.entity.QuestionTailorEntity;
import repit.repit_api_server.domain.userdata.question.entity.enums.TailorStatus;

/**
 * 면접 시작 요청의 응답.
 * 질문 재작성이 비동기라 이 시점에는 접수 사실만 알려주고, 준비가 끝나면 채팅 서버로 면접이 넘어간다.
 * 진행 상황은 GET /api/questions/tailor 로 확인한다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InterviewPrepareResponse {
    private Long interviewId;
    private String sessionId;
    // 분석 서버가 발급한 재작성 작업 id
    private String jobId;
    private TailorStatus status;
    // 채팅 서버까지 데이터가 넘어갔는지. PENDING 동안은 false다.
    private boolean chatDelivered;
    private String message;

    public static InterviewPrepareResponse of(QuestionTailorEntity tailor, String sessionId, String message) {
        return InterviewPrepareResponse.builder()
                .interviewId(tailor.getInterviewId())
                .sessionId(sessionId)
                .jobId(tailor.getJobId())
                .status(tailor.getStatus())
                .chatDelivered(Boolean.TRUE.equals(tailor.getChatDelivered()))
                .message(message)
                .build();
    }
}
