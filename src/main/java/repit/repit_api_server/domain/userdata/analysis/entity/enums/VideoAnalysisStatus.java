package repit.repit_api_server.domain.userdata.analysis.entity.enums;

import java.util.Locale;

/**
 * 영상 분석 요청의 상태.
 *
 * <p>READY / PARTIAL / UNAVAILABLE은 분석 서버가 알린 결과의 완전성이다. FAILED는 결과 없이 닫은 요청이다 —
 * 작업 자체가 실패했거나, 접수를 끝내 확인하지 못했거나, 처리 상한을 넘겼다. 사유는 error_code에 남는다.
 */
public enum VideoAnalysisStatus {
    /** 결과를 기다리는 중. 영상 하나에 하나뿐이다. */
    PENDING,
    /** 요구한 결과를 모두 받았다. */
    READY,
    /** 일부 결과만 받았다. 이것만으로는 자동 재분석하지 않는다. */
    PARTIAL,
    /** 결과를 받을 수 없다는 결과를 받았다. */
    UNAVAILABLE,
    /** 결과 없이 닫았다. */
    FAILED;

    /** 결과 본문의 status 값. ready / partial / unavailable이 아니면 null. */
    public static VideoAnalysisStatus fromResult(String value) {
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
