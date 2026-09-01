package repit.repit_api_server.domain.userdata.interview.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import repit.repit_api_server.domain.userdata.question.entity.QuestionTailorEntity;
import repit.repit_api_server.domain.userdata.question.entity.enums.TailorStatus;
import repit.repit_api_server.domain.userdata.question.preparation.FailureStage;
import repit.repit_api_server.domain.userdata.question.preparation.PreparationState;
import repit.repit_api_server.domain.userdata.question.preparation.PreparationStatus;

/**
 * 면접 시작 요청과 준비 재시도 요청의 응답.
 * 질문 준비가 비동기라 이 시점에는 접수 사실만 알려주고, 준비가 끝나면 채팅 서버로 면접이 넘어간다.
 * 진행 상황은 GET /api/questions/tailor 로 확인한다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InterviewPrepareResponse {
    private Long interviewId;
    private String sessionId;
    // 분석 서버가 발급한 질문 준비 작업 id
    private String jobId;

    /** 면접에 들어갈 수 있는지. 조회 응답과 같은 판정이다. */
    private PreparationStatus preparationStatus;
    private FailureStage failureStage;
    private boolean retryable;

    // 질문 준비 작업 자체의 상태. preparationStatus를 끌어낸 재료다.
    private TailorStatus status;
    // 채팅 서버까지 데이터가 넘어갔는지. PREPARING 동안은 false다.
    private boolean chatDelivered;
    private String message;

    public static InterviewPrepareResponse of(QuestionTailorEntity tailor, String sessionId) {
        PreparationState state = PreparationState.of(tailor);
        return InterviewPrepareResponse.builder()
                .interviewId(tailor.getInterviewId())
                .sessionId(sessionId)
                .jobId(tailor.getJobId())
                .preparationStatus(state.status())
                .failureStage(state.failureStage())
                .retryable(state.retryable())
                .status(tailor.getStatus())
                .chatDelivered(Boolean.TRUE.equals(tailor.getChatDelivered()))
                .message(messageOf(state, tailor))
                .build();
    }

    /**
     * 상태를 사람이 읽는 한 줄로 옮긴다.
     *
     * <p>실패는 단계별로 다르게 말한다. 질문을 만들지 못한 것과 만들어놓고 넘기지 못한 것은
     * 사용자가 할 수 있는 일이 다르고, 두 경우에 같은 문장을 보여주면 무엇을 기다려야 하는지
     * 알 수 없다.
     */
    private static String messageOf(PreparationState state, QuestionTailorEntity tailor) {
        if (state.status() == PreparationStatus.PREPARING) {
            return "면접에 쓸 질문을 준비하는 중입니다. 준비가 끝나면 면접이 열립니다.";
        }
        if (state.status() == PreparationStatus.READY) {
            return "면접 준비가 끝났습니다.";
        }
        if (state.failureStage() == FailureStage.QUESTION_GENERATION) {
            return tailor.getErrorMessage() == null
                    ? "질문을 준비하지 못했습니다. 잠시 후 다시 시도해주세요."
                    : tailor.getErrorMessage();
        }
        return "질문은 준비됐지만 채팅 서버에 전달하지 못했습니다. 잠시 후 다시 시도해주세요.";
    }
}
