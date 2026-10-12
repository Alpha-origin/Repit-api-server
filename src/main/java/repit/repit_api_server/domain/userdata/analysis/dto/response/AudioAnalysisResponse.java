package repit.repit_api_server.domain.userdata.analysis.dto.response;

import java.util.List;
import java.util.Map;

/**
 * 웹에 내려주는 면접 음성 분석 상태와 답변별 지표. 상태 이름은 영상 조회({@link VideoAnalysisResponse})에 맞춘다.
 *
 * @param status  면접 전체 상태. 웹은 WAITING·ANALYZING일 때만 다시 조회한다
 * @param answers 녹음이 있는 답변, 질문 진행 순서대로
 */
public record AudioAnalysisResponse(Status status, List<Answer> answers) {

    public enum Status {
        /** 분석할 답변 녹음이 없다(텍스트 면접 등). 전체 상태에만 쓴다. */
        NONE,
        /** 아직 어느 요청에도 실리지 않았다. 면접이 끝나야 요청이 나간다. */
        WAITING,
        /** 분석 중이다. */
        ANALYZING,
        READY,
        /** 일부만 결과가 있다. 진행 중이 아니라 끝난 상태다. */
        PARTIAL,
        /** 받을 수 있는 결과가 없다. */
        UNAVAILABLE,
        /** 결과 없이 닫힌 요청에 실렸다. 답변별 상태에만 쓴다. */
        FAILED
    }

    /**
     * @param questionId 지금의 질문 PK. 피드백 문항(FeedbackItemResponse)의 questionId와 같다
     * @param timing     말 속도·침묵·속도 안정성. 분석 서버가 보낸 {status, data, error} 그대로
     * @param fluency    간투사·반복 후보 등. 분석 서버가 보낸 {status, data, error} 그대로
     */
    public record Answer(String questionId, String answerId, String recordingId, Status status, Long durationMs,
                         Map<String, Object> timing, Map<String, Object> fluency, Error error) {
    }

    /** 저장된 실패 사유. JSON이 아닌 글이면 message에만 담긴다. */
    public record Error(String code, String message, Boolean retryable) {
    }
}
