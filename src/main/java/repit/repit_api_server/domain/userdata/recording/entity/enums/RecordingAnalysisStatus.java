package repit.repit_api_server.domain.userdata.recording.entity.enums;

public enum RecordingAnalysisStatus {
    // 질문·답변은 받았고 영상을 기다리는 중
    WAITING,
    // 한 곳이 차지해 분석 서버로 보내는 중
    SENDING,
    // 분석 서버가 접수함
    PENDING,
    FAILED,
    // 기다려도 영상이 하나도 오지 않아 보내지 않음
    SKIPPED
}
