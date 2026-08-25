package repit.repit_api_server.domain.metadata.entity.enums;

/** 분석 작업의 상태. 콜백이 오기 전까지는 PENDING이다. */
public enum AnalysisStatus {
    PENDING,
    SUCCEEDED,
    FAILED
}
