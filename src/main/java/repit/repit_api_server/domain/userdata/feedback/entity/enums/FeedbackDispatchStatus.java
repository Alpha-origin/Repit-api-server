package repit.repit_api_server.domain.userdata.feedback.entity.enums;

public enum FeedbackDispatchStatus {
    // 질문·답변은 받았고 답변 영상을 기다리는 중
    WAITING,
    // 한 곳이 차지해 피드백을 요청하는 중
    SENDING,
    // 요청을 마침. 접수 성공·실패는 feedback 테이블이 남긴다.
    DONE
}
