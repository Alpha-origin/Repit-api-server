package repit.repit_api_server.domain.userdata.question.preparation;

import repit.repit_api_server.domain.userdata.question.entity.QuestionTailorEntity;
import repit.repit_api_server.domain.userdata.question.entity.enums.TailorStatus;

/**
 * 준비 상태를 재작성 건 하나에서 끌어낸다.
 *
 * <p>조회 응답, 면접 시작 응답, SSE 실패 이벤트가 모두 같은 판정을 써야 한다. 세 곳이 각자
 * 조합하면 같은 상황을 두고 서로 다른 상태를 내려보낸다.
 *
 * @param retryAfterMs 다시 시도하기까지 기다려야 하는 시간. 지금은 곧바로 시도할 수 있어 늘 비어 있다.
 */
public record PreparationState(PreparationStatus status,
                               FailureStage failureStage,
                               boolean retryable,
                               Long retryAfterMs) {

    private static final PreparationState NOT_REQUESTED =
            new PreparationState(PreparationStatus.NOT_REQUESTED, null, false, null);
    private static final PreparationState PREPARING =
            new PreparationState(PreparationStatus.PREPARING, null, false, null);
    private static final PreparationState READY =
            new PreparationState(PreparationStatus.READY, null, false, null);

    public static PreparationState notRequested() {
        return NOT_REQUESTED;
    }

    /**
     * 두 단계 모두 재시도 경로가 있어 retryable은 참이다. 경로가 없는 실패에까지 참을 내리면
     * 클라이언트는 눌러도 아무 일도 일어나지 않는 버튼을 보여주게 된다.
     */
    public static PreparationState failed(FailureStage stage) {
        return new PreparationState(PreparationStatus.FAILED, stage, true, null);
    }

    /**
     * 아직 확정되지 않았으면 준비 중, 채팅 서버까지 넘어갔으면 준비 완료다. 그 밖은 실패이고,
     * 면접에 쓸 질문이 남아 있는지로 어느 단계에서 멈췄는지가 갈린다.
     *
     * <p>넘기는 중인 건은 전달 실패 사유가 지워졌는지까지 함께 본다. 전달은 권리를 먼저
     * 차지하고 시작하므로, 표시만 보면 아직 열리지도 않은 면접을 열렸다고 말하게 된다.
     */
    public static PreparationState of(QuestionTailorEntity tailor) {
        if (tailor == null) {
            return notRequested();
        }
        if (tailor.getStatus() == TailorStatus.PENDING) {
            return PREPARING;
        }
        if (Boolean.TRUE.equals(tailor.getChatDelivered()) && tailor.getChatErrorMessage() == null) {
            return READY;
        }
        // 1:1 실패는 원질문이 폴백으로 들어차 있어 여기 걸리지 않는다. 질문이 비는 것은
        // 폴백 없이 닫힌 N:1뿐이고, 그때는 질문부터 다시 만들어야 한다.
        if (tailor.getQuestions() == null || tailor.getQuestions().isEmpty()) {
            return failed(FailureStage.QUESTION_GENERATION);
        }
        return failed(FailureStage.CHAT_DELIVERY);
    }
}
