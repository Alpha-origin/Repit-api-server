package repit.repit_api_server.domain.userdata.interview.service;

import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.global.exception.BusinessException;

/**
 * 면접 기록으로 채점·기업 코멘트·음성/영상 분석을 실행해도 되는지 정하는 한 곳.
 *
 * <p>판단은 이 서버에 저장된 면접 상태로만 한다. 서버가 정상 완료(COMPLETED)로 확정한 면접만 실행한다. 중단(ABANDONED)은
 * 기록만 남기고 실행하지 않으며, 진행 중(IN_PROGRESS)은 끝날 때까지 미룬다. 소켓 끊김, 완료 화면 진입, 답변 개수, 녹음의
 * 종료 이유로는 판단하지 않는다 — 브라우저가 보낸 신호라 서버가 확정한 상태가 아니다.
 *
 * <p>컨트롤러에만 두지 않는다. 스윕·재전송·작업 조회 복구·늦은 콜백처럼 사용자 요청 없이 도는 길도 같은 판단을 거친다.
 */
public final class InterviewFeedbackPolicy {

    /** 사용자 요청 없이 도는 길(스윕·업로드 뒤·콜백)이 따를 판단. */
    public enum Decision {
        /** 정상 완료. 실행한다. */
        RUN,
        /** 중단됐거나 면접이 없다. 실행하지 않고, 대기 작업은 정책 사유로 닫는다. 장애가 아니므로 재시도하지 않는다. */
        SKIP,
        /** 아직 끝나지 않았다. 지우거나 닫지 않고 완료를 기다린다. */
        HOLD
    }

    private InterviewFeedbackPolicy() {
    }

    public static boolean isEligible(InterviewEntity interview) {
        return interview != null && interview.getStatus() == Status.COMPLETED;
    }

    public static Decision decide(InterviewEntity interview) {
        if (isEligible(interview)) {
            return Decision.RUN;
        }
        if (interview == null || interview.getStatus() == Status.ABANDONED) {
            return Decision.SKIP;
        }
        return Decision.HOLD;
    }

    /**
     * 사용자 요청(수동 생성·재분석·단건 조회)이 대상이 아니면 409로 막는다. 소유권을 확인한 뒤에 부른다 — 남의 면접이나
     * 없는 면접을 409로 답하면 인증·권한 오류가 정책 오류로 가려진다.
     */
    public static void requireEligible(InterviewEntity interview) {
        if (!isEligible(interview)) {
            throw notEligible(interview);
        }
    }

    /** 대상이 아닌 면접에 돌려줄 오류. 기존 오류 envelope 그대로 409다. */
    public static BusinessException notEligible(InterviewEntity interview) {
        if (interview != null && interview.getStatus() == Status.ABANDONED) {
            return BusinessException.conflict("중단된 면접은 피드백을 생성하지 않습니다.");
        }
        return BusinessException.conflict("면접이 아직 끝나지 않았습니다. 완료된 면접만 피드백을 요청할 수 있습니다.");
    }
}
