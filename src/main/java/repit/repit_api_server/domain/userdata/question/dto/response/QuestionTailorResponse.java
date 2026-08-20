package repit.repit_api_server.domain.userdata.question.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import repit.repit_api_server.domain.userdata.question.entity.QuestionTailorEntity;
import repit.repit_api_server.domain.userdata.question.entity.enums.TailorStatus;

import java.util.List;

/**
 * 면접 시작 뒤 클라이언트가 폴링하는 준비 상태.
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
    // 채팅 서버까지 데이터가 넘어갔는지. 이게 true여야 면접을 열 수 있다.
    private boolean chatDelivered;
    // 면접에 실제로 사용할 질문
    private List<TailoredQuestionResponse> questions;
    // 재작성 전 원질문. 재작성 결과와 대조할 수 있게 함께 내려준다.
    private List<TailoredQuestionResponse> originalQuestions;
    private String errorMessage;

    public static QuestionTailorResponse of(QuestionTailorEntity tailor) {
        List<TailoredQuestionResponse> source = tailor.getSourceQuestions() == null
                ? List.of()
                : tailor.getSourceQuestions();
        // PENDING 동안에는 최종 질문이 아직 없으므로 원질문을 그대로 보여준다.
        List<TailoredQuestionResponse> questions = tailor.getQuestions() == null ? source : tailor.getQuestions();

        return QuestionTailorResponse.builder()
                .interviewId(tailor.getInterviewId())
                .jobId(tailor.getJobId())
                .status(tailor.getStatus())
                .tailored(Boolean.TRUE.equals(tailor.getTailored()))
                .chatDelivered(Boolean.TRUE.equals(tailor.getChatDelivered()))
                .questions(questions)
                .originalQuestions(source)
                .errorMessage(tailor.getErrorMessage())
                .build();
    }

    // 아직 면접을 시작하지 않은 경우. 원질문만 보여준다.
    public static QuestionTailorResponse notRequested(Long interviewId, List<TailoredQuestionResponse> questions) {
        return QuestionTailorResponse.builder()
                .interviewId(interviewId)
                .status(TailorStatus.NOT_REQUESTED)
                .tailored(false)
                .chatDelivered(false)
                .questions(questions)
                .originalQuestions(questions)
                .build();
    }
}
