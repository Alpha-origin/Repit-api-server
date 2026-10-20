package repit.repit_api_server.domain.userdata.analysis.service;

import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.AnswerMode;
import repit.repit_api_server.domain.userdata.interview.service.InterviewFeedbackPolicy;

/**
 * 면접 영상을 행동 분석해도 되는지. 공통 정책({@link InterviewFeedbackPolicy})에 텍스트 면접 제외를 더한다.
 *
 * <p>요청 생성·재전송·작업 조회 복구·자동/사용자 재분석·조회·늦은 콜백이 모두 이 판단을 거친다. 텍스트 면접 제외는 행동
 * 분석만의 규칙이다 — 정상 완료된 텍스트 면접도 내용 채점은 한다.
 */
final class VideoAnalysisEligibility {

    // 결과 없이 닫은 요청의 error_code. 화면에는 나가지 않는다(중단 면접은 조회가 409, 텍스트 면접은 NONE이다).
    static final String INTERVIEW_ABANDONED = "INTERVIEW_ABANDONED";
    static final String INTERVIEW_NOT_FOUND = "INTERVIEW_NOT_FOUND";
    static final String TEXT_INTERVIEW = "TEXT_INTERVIEW";

    enum Kind {
        /** 정상 완료된 음성 면접. 분석한다. */
        RUN,
        /** 중단·텍스트·없는 면접. 분석하지 않고 대기 요청은 정책 사유로 닫는다. */
        SKIP,
        /** 아직 끝나지 않았다. 닫지 않고 기다린다. */
        HOLD
    }

    record Verdict(Kind kind, String code, String message) {
    }

    private VideoAnalysisEligibility() {
    }

    static Verdict of(InterviewEntity interview) {
        if (interview == null) {
            return new Verdict(Kind.SKIP, INTERVIEW_NOT_FOUND, "면접을 찾을 수 없습니다.");
        }
        return switch (InterviewFeedbackPolicy.decide(interview)) {
            case HOLD -> new Verdict(Kind.HOLD, null, "면접이 아직 끝나지 않았습니다.");
            case SKIP -> new Verdict(Kind.SKIP, INTERVIEW_ABANDONED, "중단된 면접이라 영상 분석을 하지 않습니다.");
            case RUN -> isText(interview)
                    ? new Verdict(Kind.SKIP, TEXT_INTERVIEW, "텍스트 면접은 영상 행동 분석을 하지 않습니다.")
                    : new Verdict(Kind.RUN, null, null);
        };
    }

    /** 답변 방식이 비어 있는 옛 면접은 음성 면접으로 본다(답변 방식이 생기기 전에는 음성 면접뿐이었다). */
    static boolean isText(InterviewEntity interview) {
        return interview.getAnswerMode() == AnswerMode.TEXT;
    }
}
