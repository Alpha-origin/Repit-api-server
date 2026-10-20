package repit.repit_api_server.domain.userdata.analysis.behavior;

import java.util.Map;
import java.util.Set;

import static repit.repit_api_server.domain.userdata.analysis.behavior.StrictJson.*;

/**
 * 분석 서버의 영상 분석 종료 문서(콜백 본문, 작업 조회의 result) 전체를 검증한다.
 *
 * <p>포장의 상태가 서로 맞는지 본다 — 최상위·result·result.analysis의 status가 같고, ready/partial은 행동 데이터가 있고 오류가
 * 없으며, unavailable은 데이터 없이 공개 오류가 있고, failed는 result 없이 최상위 오류만 있다. 행동 데이터는
 * {@link BehaviorValidator}로 검증하고, 데이터의 videoId·durationMs가 바깥 result와 요청한 영상에 묶여 있는지, 콜백 상태가
 * 데이터의 품질 판정(ready/partial)과 같은지까지 본다.
 *
 * <p>요청·작업·세션·면접·사용자 id의 대조는 부르는 쪽({@code VideoAnalysisCallbackHandler})이 먼저 한다. 그쪽은 어긋난
 * 이유를 따로(ID_MISMATCH) 남기기 때문이다. 포장에 계약에 없는 필드가 더 있으면 받는다(하위 호환). 행동 데이터 안은 고정
 * 계약이라 받지 않는다.
 */
public final class VideoResultDocument {

    public static final String SCHEMA_VERSION = "1";
    public static final Set<String> STATUSES = Set.of("ready", "partial", "unavailable", "failed");

    /**
     * 검증을 통과한 문서에서 저장·표시에 쓰는 값.
     *
     * @param status         ready / partial / unavailable / failed
     * @param errorCode      공개 오류 코드. ready/partial이면 null
     * @param errorRetryable 공개 사전의 retryable. ready/partial이면 null
     * @param errorMessage   분석 서버가 보낸 메시지. 내부 기록용이며 화면에는 쓰지 않는다
     * @param behavior       ready/partial의 행동 데이터
     */
    public record Accepted(String status, String errorCode, Boolean errorRetryable, String errorMessage,
                           BehaviorData behavior) {
    }

    private VideoResultDocument() {
    }

    /**
     * @param body            콜백 본문(또는 작업 조회의 result)
     * @param expectedVideoId 이 요청으로 보낸 영상(recording_id)
     */
    public static Accepted validate(Map<String, Object> body, String expectedVideoId) {
        require(!JsonValues.containsNul(body), "$", "NUL character in string");
        constant(body.get("schemaVersion"), SCHEMA_VERSION, "$.schemaVersion");
        Object rawStatus = body.get("status");
        require(rawStatus instanceof String && STATUSES.contains(rawStatus), "$.status", "unknown status");
        String status = (String) rawStatus;

        if (status.equals("failed")) {
            nullField(body, "result", "$");
            String code = VideoPublicErrors.validateRemote(body.get("error"), "$.error");
            return failure(status, code, body.get("error"));
        }

        nullField(body, "error", "$");
        Map<String, Object> result = object(body.get("result"), "$.result");
        for (String key : new String[]{"videoId", "durationMs", "status", "analysis", "error"}) {
            require(result.containsKey(key), "$.result." + key, "missing field");
        }
        String videoId = text(result.get("videoId"), 200, "$.result.videoId");
        require(videoId.equals(expectedVideoId), "$.result.videoId", "video mismatch");
        require(status.equals(result.get("status")), "$.result.status", "status mismatch");
        Long durationMs = result.get("durationMs") == null
                ? null
                : integer(result.get("durationMs"), 1, BehaviorPolicy.MAX_DURATION_MS, "$.result.durationMs");

        Map<String, Object> analysis = object(result.get("analysis"), "$.result.analysis");
        for (String key : new String[]{"status", "data", "error"}) {
            require(analysis.containsKey(key), "$.result.analysis." + key, "missing field");
        }
        require(status.equals(analysis.get("status")), "$.result.analysis.status", "status mismatch");

        if (status.equals("unavailable")) {
            nullField(analysis, "data", "$.result.analysis");
            String code = VideoPublicErrors.validateRemote(analysis.get("error"), "$.result.analysis.error");
            if (result.get("error") != null) {
                // 파일 문제는 같은 공개 원인이 result.error와 analysis.error에 함께 온다.
                VideoPublicErrors.validateRemote(result.get("error"), "$.result.error");
                require(JsonValues.same(result.get("error"), analysis.get("error")), "$.result.error",
                        "file error consistency");
            }
            return failure(status, code, analysis.get("error"));
        }

        // ready / partial: 부분 결과도 종료다. 지표별 미측정 사유는 데이터 안에 있고 오류는 없다.
        nullField(analysis, "error", "$.result.analysis");
        nullField(result, "error", "$.result");
        BehaviorData data;
        try {
            data = BehaviorValidator.parse(analysis.get("data"));
        } catch (VideoResultException e) {
            throw new VideoResultException("$.result.analysis.data" + e.path().substring(1), e.rule());
        }
        require(data.videoId().equals(videoId), "$.result.analysis.data.videoId", "video binding mismatch");
        require(durationMs != null && durationMs == data.durationMs(), "$.result.durationMs", "duration binding mismatch");
        require(BehaviorValidator.quality(data).equals(status), "$.status", "quality status mismatch");
        return new Accepted(status, null, null, null, data);
    }

    private static Accepted failure(String status, String code, Object error) {
        Object message = ((Map<?, ?>) error).get("message");
        return new Accepted(status, code, VideoPublicErrors.retryableOf(code), (String) message, null);
    }
}
