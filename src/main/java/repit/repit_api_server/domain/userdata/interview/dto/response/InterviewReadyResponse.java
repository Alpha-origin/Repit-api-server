package repit.repit_api_server.domain.userdata.interview.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import repit.repit_api_server.domain.userdata.question.preparation.FailureStage;
import repit.repit_api_server.domain.userdata.question.preparation.PreparationState;
import repit.repit_api_server.domain.userdata.question.preparation.PreparationStatus;

/**
 * 면접 준비가 끝났음을, 또는 끝내 열지 못했음을 알리는 SSE 페이로드.
 *
 * <p>웹이 채팅 서버에 붙는 데 필요한 것만 담는다. 질문 본문은 넣지 않는다 — 면접 중에는
 * 채팅 서버가 한 문항씩 내려주고, 채점 기준인 의도까지 미리 내려가면 지원자가 거기 맞춰
 * 답을 준비할 수 있다.
 *
 * <p>실패도 반드시 흘려보낸다. 아무것도 보내지 않으면 구독은 15분 타임아웃까지 매달려 있고,
 * 사용자는 면접이 준비되는 중인지 이미 실패했는지 구분할 수 없다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InterviewReadyResponse {
    private Long interviewId;
    private String sessionId;
    // 재작성본으로 여는지, 원질문 폴백으로 여는지. 어느 쪽이든 면접은 열린다.
    private Boolean tailored;

    // 조회 응답(GET /api/questions/tailor)과 같은 판정이다. 두 경로가 다른 말을 하면 안 된다.
    private PreparationStatus preparationStatus;
    // 실패한 경우에만 채워진다. 이 값에 따라 재시도가 하는 일이 갈린다.
    private FailureStage failureStage;
    private Boolean retryable;

    private String message;

    // 실패한 경우에만 채워진다.
    private String errorMessage;

    public static InterviewReadyResponse ready(Long interviewId, String sessionId, Boolean tailored) {
        return InterviewReadyResponse.builder()
                .interviewId(interviewId)
                .sessionId(sessionId)
                .tailored(tailored)
                .preparationStatus(PreparationStatus.READY)
                .retryable(false)
                .message("면접 준비가 끝났습니다.")
                .build();
    }

    /**
     * 어느 단계에서 멈췄는지까지 실어 보낸다. 단계를 빼면 웹은 질문을 다시 만들어야 하는지
     * 전달만 다시 하면 되는지 모른 채 같은 버튼을 보여주게 된다.
     */
    public static InterviewReadyResponse failed(Long interviewId, FailureStage stage, String errorMessage) {
        PreparationState state = PreparationState.failed(stage);
        return InterviewReadyResponse.builder()
                .interviewId(interviewId)
                .preparationStatus(state.status())
                .failureStage(stage)
                .retryable(state.retryable())
                .message(messageOf(stage))
                .errorMessage(errorMessage)
                .build();
    }

    private static String messageOf(FailureStage stage) {
        if (stage == FailureStage.QUESTION_GENERATION) {
            return "면접 질문을 준비하지 못했습니다. 다시 시도해주세요.";
        }
        return "질문은 준비됐지만 채팅 서버에 전달하지 못했습니다. 잠시 후 다시 시도해주세요.";
    }
}
