package repit.repit_api_server.domain.userdata.feedback.entity.enums;

public enum FeedbackDispatchStatus {
    // 답변 영상을 기다리거나, 일시적 실패 뒤 다시 시도를 기다리는 중
    WAITING,
    // 한 곳이 차지해 피드백을 요청하는 중. 차지한 뒤 오래 끝나지 않으면 WAITING으로 되돌린다.
    SENDING,
    // 채점이 접수됐거나 이미 접수돼 있었다.
    DONE,
    // 다시 시도해도 소용없는 오류거나 시도 한도를 넘었다. 사유는 lastError에 남는다.
    FAILED
}
