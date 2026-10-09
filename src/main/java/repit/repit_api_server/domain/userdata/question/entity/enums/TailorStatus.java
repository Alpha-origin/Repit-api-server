package repit.repit_api_server.domain.userdata.question.entity.enums;

public enum TailorStatus {
    // NOT_REQUESTED는 조회 응답 전용이라 DB에 저장되지 않는다(question_tailor 체크 제약 참고).
    NOT_REQUESTED,
    // 꺼낼 세트가 없어 다음 질문 사이클을 기다린다. 사이클 콜백이 이어서 재작성을 요청한다.
    WAITING,
    PENDING,
    SUCCEEDED,
    FAILED
}
