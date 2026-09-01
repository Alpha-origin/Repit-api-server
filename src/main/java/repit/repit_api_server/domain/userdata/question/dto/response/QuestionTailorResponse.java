package repit.repit_api_server.domain.userdata.question.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.question.entity.QuestionTailorEntity;
import repit.repit_api_server.domain.userdata.question.entity.enums.TailorStatus;
import repit.repit_api_server.domain.userdata.question.preparation.FailureStage;
import repit.repit_api_server.domain.userdata.question.preparation.PreparationState;
import repit.repit_api_server.domain.userdata.question.preparation.PreparationStatus;

import java.util.List;

/**
 * 면접 시작 뒤 클라이언트가 폴링하는 준비 상태.
 *
 * <p>판단은 {@code preparationStatus} 하나로 끝난다. {@code status}와 {@code chatDelivered}는
 * 그 판정의 재료라 함께 내려주지만, 둘을 조합해 면접을 열지 말지 정하는 것은 서버 몫이다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionTailorResponse {
    private Long interviewId;
    private String jobId;

    /** 면접에 들어갈 수 있는지. 클라이언트는 이 값만 보면 된다. */
    private PreparationStatus preparationStatus;
    /** 실패한 단계. 실패가 아니면 비어 있다. */
    private FailureStage failureStage;
    /** 재시도 API로 다시 시도할 수 있는지. */
    private boolean retryable;
    /** 다시 시도하기까지 기다려야 하는 시간. 지금은 늘 비어 있다. */
    private Long retryAfterMs;

    // 질문 재작성 작업 자체의 상태. preparationStatus를 끌어낸 재료다.
    private TailorStatus status;
    // 재작성본인지 원질문인지. 폴백이면 false다.
    private boolean tailored;
    // 채팅 서버까지 데이터가 넘어갔는지. preparationStatus를 끌어낸 재료다.
    private boolean chatDelivered;

    // 면접에 실제로 사용할 질문
    private List<TailoredQuestionResponse> questions;
    // 재작성 전 원질문. 재작성 결과와 대조할 수 있게 함께 내려준다.
    private List<TailoredQuestionResponse> originalQuestions;
    // questions의 개수. 질문을 감춘 경우에는 0이다.
    private int questionCount;

    private String errorMessage;

    public static QuestionTailorResponse of(QuestionTailorEntity tailor) {
        PreparationState state = PreparationState.of(tailor);

        List<TailoredQuestionResponse> source = tailor.getSourceQuestions() == null
                ? List.of()
                : tailor.getSourceQuestions();
        // PENDING 동안에는 최종 질문이 아직 없으므로 원질문을 그대로 보여준다.
        List<TailoredQuestionResponse> questions = tailor.getQuestions() == null ? source : tailor.getQuestions();

        if (hidesQuestions(tailor)) {
            source = List.of();
            questions = List.of();
        }

        return QuestionTailorResponse.builder()
                .interviewId(tailor.getInterviewId())
                .jobId(tailor.getJobId())
                .preparationStatus(state.status())
                .failureStage(state.failureStage())
                .retryable(state.retryable())
                .retryAfterMs(state.retryAfterMs())
                .status(tailor.getStatus())
                .tailored(Boolean.TRUE.equals(tailor.getTailored()))
                .chatDelivered(Boolean.TRUE.equals(tailor.getChatDelivered()))
                .questions(questions)
                .originalQuestions(source)
                .questionCount(questions.size())
                // 어디서 멈췄든 사유가 응답에 남아야 한다. 채팅 서버 전달 실패는 재작성 쪽 사유가
                // 비어 있어, 그것만 실으면 열리지 않는 이유가 통째로 사라진다.
                .errorMessage(tailor.getErrorMessage() == null
                        ? tailor.getChatErrorMessage()
                        : tailor.getErrorMessage())
                .build();
    }

    /**
     * 면접이 열리기 전의 N:1 질문은 내려보내지 않는다.
     *
     * <p>N:1이 실패로 닫히면 최종 질문이 비고, 그 자리는 기술 면접관 몫으로 남겨둔 원질문
     * 두 개로 채워진다. 그대로 내려주면 웹은 질문이 있다고 보고 기술 질문 두 개짜리 면접을
     * N:1인 척 연다 — 서버가 열지 않기로 한 바로 그 면접이다.
     *
     * <p>준비가 끝나기 전에는 성공한 N:1도 감춘다. 면접 중에 한 문항씩 나가야 할 질문이
     * 시작 전에 통째로 보이면 지원자가 미리 답을 맞춰둘 수 있다.
     */
    private static boolean hidesQuestions(QuestionTailorEntity tailor) {
        return tailor.getMode() == InterviewMode.MULTI && !Boolean.TRUE.equals(tailor.getChatDelivered());
    }

    // 아직 면접을 시작하지 않은 경우. 원질문만 보여준다.
    public static QuestionTailorResponse notRequested(Long interviewId, List<TailoredQuestionResponse> questions) {
        PreparationState state = PreparationState.notRequested();
        return QuestionTailorResponse.builder()
                .interviewId(interviewId)
                .preparationStatus(state.status())
                .retryable(state.retryable())
                .status(TailorStatus.NOT_REQUESTED)
                .tailored(false)
                .chatDelivered(false)
                .questions(questions)
                .originalQuestions(questions)
                .questionCount(questions.size())
                .build();
    }
}
