package repit.repit_api_server.domain.userdata.analysis.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import repit.repit_api_server.domain.userdata.analysis.behavior.BehaviorData;
import repit.repit_api_server.domain.userdata.analysis.behavior.VideoPublicError;

import java.util.Map;

/**
 * 웹에 내려주는 면접 영상 분석 상태와 행동 관찰 결과.
 *
 * <p>상태(status)는 최종 영상의 가장 최근 요청을 따르고, 행동 데이터(behavior)는 같은 영상의 요청 가운데 검증을 통과한 가장
 * 최근 ready/partial 결과를 따로 고른다. 재분석 중이거나 최근 요청이 실패해도 직전 결과를 계속 보여 줄 수 있게 하기 위해서다.
 * 두 값이 같은 요청에서 왔다고 가정하지 않는다 — 어느 요청인지는 latestAnalysisId·behaviorAnalysisId로 밝힌다. 다른 영상의
 * 결과는 섞지 않는다.
 *
 * <p>값이 없는 필드도 키를 빼지 않고 null로 보낸다. 웹은 키가 없으면 행동 데이터를 모르는 옛 API로 본다.
 *
 * @param status                아래 {@link Status}
 * @param result                하위 호환용. 같은 영상의 가장 최근 완료(ready/partial/unavailable) 콜백 원문. 새 화면은 쓰지 않는다
 * @param canRetry              지금 재분석을 요청할 수 있는지. PARTIAL·FAILED에서 정책·원본·한도를 모두 만족할 때만 true
 * @param reasonCode            상태의 공개 사유. NONE의 이유, FAILED의 공개 오류 코드, 읽을 수 없는 옛 결과
 *                              (RESULT_SCHEMA_UNSUPPORTED). 없으면 null
 * @param error                 status가 FAILED일 때 최근 요청의 공개 오류. 아니면 null
 * @param behavior              검증한 행동 데이터. 없으면 null
 * @param latestAnalysisId      최신 요청 id. 요청이 없으면 null
 * @param behaviorAnalysisId    behavior를 만든 요청 id. behavior가 없으면 null
 * @param showingPreviousResult behavior가 최신 요청이 아니라 같은 영상의 이전 요청 결과인지
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record VideoAnalysisResponse(
        Status status,
        Map<String, Object> result,
        boolean canRetry,
        String reasonCode,
        VideoPublicError error,
        BehaviorData behavior,
        String latestAnalysisId,
        String behaviorAnalysisId,
        boolean showingPreviousResult) {

    /** 요청이 없어 보여 줄 것이 없는 상태. */
    public static VideoAnalysisResponse empty(Status status, String reasonCode) {
        return new VideoAnalysisResponse(status, null, false, reasonCode, null, null, null, null, false);
    }

    public enum Status {
        /** 보여 줄 분석이 없다. 이유는 reasonCode(텍스트 면접, 녹화 없음, 켜기 전 녹화, 보관 기간 지남, 기능 꺼짐). */
        NONE,
        /** 영상은 있고 아직 요청 전이다. */
        WAITING,
        /** 분석 중이다. 자동 재분석을 기다리는 중도 여기에 든다. */
        ANALYZING,
        READY,
        /** 일부 지표만 측정됐다. 진행 중이 아니라 끝난 상태다. */
        PARTIAL,
        /** 결과 없이 끝났다. 재분석할 수 있는지는 canRetry로 본다. */
        FAILED
    }
}
