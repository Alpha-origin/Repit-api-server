package repit.repit_api_server.domain.userdata.analysis.dto.response;

import java.util.Map;

/**
 * 웹에 내려주는 면접 영상 분석 상태. 웹은 status만 보고 화면을 그린다.
 *
 * @param status   아래 {@link Status}
 * @param result   보여 줄 결과(최종 영상의 가장 최근 완료 결과). 재분석 중이면 직전 결과가 실린다. 없으면 null
 * @param canRetry 지금 재분석을 요청할 수 있는지
 */
public record VideoAnalysisResponse(Status status, Map<String, Object> result, boolean canRetry) {

    public enum Status {
        /** 면접 화면 녹화가 없거나, 보관 기간이 지나 분석할 수 없다. */
        NONE,
        /** 영상은 있고 아직 요청 전이다. */
        WAITING,
        /** 분석 중이다. 자동 재분석을 기다리는 중도 여기에 든다. */
        ANALYZING,
        READY,
        PARTIAL,
        /** 결과 없이 끝났다. 재분석할 수 있는지는 canRetry로 본다. */
        FAILED
    }
}
