package repit.repit_api_server.domain.userdata.analysis.dto.response;

import java.util.List;
import java.util.Map;

/**
 * 웹에 내려주는 면접 음성 분석 상태와 답변별 지표. 상태 이름은 웹 계약(docs/interview-recording.md)을 따른다.
 *
 * @param status   면접 전체 상태. 웹은 PENDING일 때만 다시 조회한다
 * @param answers  녹음이 있는 답변, 질문 진행 순서대로
 * @param canRetry 지금 음성 분석을 다시 요청할 수 있는지
 */
public record AudioAnalysisResponse(Status status, List<Answer> answers, boolean canRetry) {

    public enum Status {
        /** 아직 요청 전이거나 분석 중이다. 다시 분석하는 중도 여기다. 이것만 진행 중이고 나머지는 끝난 상태다. */
        PENDING,
        READY,
        /** 일부만 결과가 있다. 진행 중이 아니라 끝난 상태다. */
        PARTIAL,
        /** 받을 수 있는 결과가 없다. 녹음이 없는 면접(텍스트 면접 등)도 여기다. */
        UNAVAILABLE,
        /** 결과 없이 닫혔다 — 요청이 닫혔거나 음성 분석 요청을 포기했다. */
        FAILED
    }

    /**
     * @param questionId 지금의 질문 PK. 피드백 문항(FeedbackItemResponse)의 questionId와 같다
     * @param requestId  이 답변의 상태·지표를 낸 분석 요청 id. 결과가 있으면 그 결과의 요청, 없거나 다시 분석하는
     *                   중이면 이 녹음을 실은 가장 나중 요청이다. 아직 요청 전이면 null
     * @param timing     말 속도·침묵·속도 안정성. 분석 서버가 보낸 {status, data, error} 그대로
     * @param fluency    간투사·반복 후보 등. 분석 서버가 보낸 {status, data, error} 그대로
     */
    public record Answer(String questionId, String answerId, String recordingId, String requestId, Status status,
                         Long durationMs, Map<String, Object> timing, Map<String, Object> fluency, Error error) {
    }

    /** 저장된 실패 사유. JSON이 아닌 글이면 message에만 담긴다. */
    public record Error(String code, String message, Boolean retryable) {
    }
}
