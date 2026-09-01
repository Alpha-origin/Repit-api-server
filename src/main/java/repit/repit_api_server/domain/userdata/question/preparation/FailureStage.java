package repit.repit_api_server.domain.userdata.question.preparation;

/**
 * 면접 준비가 멈춘 단계. 단계에 따라 재시도가 하는 일이 다르다.
 */
public enum FailureStage {
    /**
     * 질문을 만들지 못했다. 다시 하려면 분석 서버에 질문 구성을 새로 요청해야 한다.
     * N:1은 폴백할 원질문이 없어 이 단계에서 멈추면 면접이 열리지 않는다.
     */
    QUESTION_GENERATION,
    /** 질문은 준비됐지만 채팅 서버에 면접을 열지 못했다. 질문을 다시 만들 필요 없이 전달만 다시 하면 된다. */
    CHAT_DELIVERY
}
