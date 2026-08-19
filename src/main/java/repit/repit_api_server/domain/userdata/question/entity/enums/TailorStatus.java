package repit.repit_api_server.domain.userdata.question.entity.enums;

public enum TailorStatus {
    // NOT_REQUESTED는 조회 응답 전용이라 DB에 저장되지 않는다(question_tailor 체크 제약 참고).
    NOT_REQUESTED,
    PENDING,
    SUCCEEDED,
    FAILED
}
