package repit.repit_api_server.domain.userdata.analysis.entity.enums;

/** 영상 분석 묶음을 누가 열었는지. 사용자 재분석 횟수를 이것으로 센다. */
public enum VideoAnalysisRequestedBy {
    /** 업로드 뒤 자동으로 연 묶음. */
    AUTO,
    /** 사용자가 재분석으로 연 묶음. */
    USER
}
