package repit.repit_api_server.domain.userdata.feedback.entity.enums;

/**
 * 면접이 끝난 뒤 분석 서버에 맡기는 일의 종류. 면접마다 종류별로 대기 행이 하나씩 있다.
 */
public enum FeedbackDispatchKind {
    /** 질문·답변 채점. 면접 기록만 있으면 되므로 기록이 오는 순간 요청한다. */
    FEEDBACK,
    /** 답변 음성 분석. 답한 질문마다 음성이 모일 때까지, 덜 모이면 조용해질 때까지 미룬다. */
    AUDIO_ANALYSIS
}
