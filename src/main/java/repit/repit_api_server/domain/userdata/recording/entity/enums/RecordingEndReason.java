package repit.repit_api_server.domain.userdata.recording.entity.enums;

import java.util.Locale;

/**
 * 웹이 답변 녹음을 끝낸 이유. 분석 서버에는 소문자 이름으로 넘긴다.
 */
public enum RecordingEndReason {
    /** 사용자가 직접 답변을 마쳤다. */
    USER,
    /** 답변 시간 제한에 걸렸다. */
    TIMEOUT,
    /** 오류나 연결 문제로 중간에 끊겼다. */
    INTERRUPTED,
    /** 이유를 알 수 없다. */
    UNKNOWN;

    /** 분석 서버가 받는 값. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
