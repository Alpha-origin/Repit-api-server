package repit.repit_api_server.domain.userdata.question.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import repit.repit_api_server.domain.userdata.question.entity.QuestionTailorEntity;
import repit.repit_api_server.domain.userdata.question.entity.enums.TailorStatus;

import java.util.List;

/**
 * 면접 시작 직전 클라이언트가 폴링하는 응답.
 * 재작성이 끝나지 않았거나 실패했어도 {@code questions}에는 항상 쓸 수 있는 질문이 담긴다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionTailorResponse {
    private Long interviewId;
    private String jobId;
    private TailorStatus status;
    // 재작성본인지 원질문인지. 폴백이면 false다.
    private boolean tailored;
    private List<TailoredQuestionResponse> questions;
    private String errorMessage;

    public static QuestionTailorResponse of(QuestionTailorEntity tailor, List<TailoredQuestionResponse> questions) {
        return QuestionTailorResponse.builder()
                .interviewId(tailor.getInterviewId())
                .jobId(tailor.getJobId())
                .status(tailor.getStatus())
                .tailored(Boolean.TRUE.equals(tailor.getTailored()))
                .questions(questions)
                .errorMessage(tailor.getErrorMessage())
                .build();
    }

    // 아직 재작성을 요청하지 않은 면접. 원질문을 그대로 쓰면 된다.
    public static QuestionTailorResponse notRequested(Long interviewId, List<TailoredQuestionResponse> questions) {
        return QuestionTailorResponse.builder()
                .interviewId(interviewId)
                .status(TailorStatus.NOT_REQUESTED)
                .tailored(false)
                .questions(questions)
                .build();
    }
}
