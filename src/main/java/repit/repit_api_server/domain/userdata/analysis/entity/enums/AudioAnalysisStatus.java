package repit.repit_api_server.domain.userdata.analysis.entity.enums;

import java.util.Locale;

/**
 * 음성 분석 요청의 상태.
 *
 * <p>READY / PARTIAL / UNAVAILABLE은 분석 서버가 콜백으로 알린 결과의 완전성이다. 분석 서버는 결과를
 * 하나라도 만들 수 있으면 partial로, 하나도 없으면 unavailable로 콜백하므로 "실패"라는 값을 따로 두지 않는다.
 */
public enum AudioAnalysisStatus {
    /** 접수됐고 콜백을 기다리는 중. */
    PENDING,
    /** 요구한 분석 결과를 모두 받았다. */
    READY,
    /** 일부 분석 결과만 받았다. */
    PARTIAL,
    /** 받을 수 있는 분석 결과가 없다. */
    UNAVAILABLE,
    /** 콜백이 제때 오지 않았다. 이 요청에 실었던 녹음은 다시 요청할 수 있다. */
    FAILED;

    /** 콜백의 status 값. ready / partial / unavailable이 아니면 null. */
    public static AudioAnalysisStatus fromCallback(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "ready" -> READY;
            case "partial" -> PARTIAL;
            case "unavailable" -> UNAVAILABLE;
            default -> null;
        };
    }
}
