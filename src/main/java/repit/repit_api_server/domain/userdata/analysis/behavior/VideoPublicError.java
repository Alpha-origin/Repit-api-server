package repit.repit_api_server.domain.userdata.analysis.behavior;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 웹에 보여 줄 영상 분석 오류. 코드별 공개 사전에서만 만든다 — DB에 남긴 내부 메시지, 예외 문자열, 분석 서버 응답 본문을
 * 그대로 내보내지 않는다.
 *
 * @param retryable 같은 원본을 새 요청으로 다시 분석하면 나을 수 있는 원인인지. 재분석 버튼을 열지는 canRetry가 정한다.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record VideoPublicError(String code, String message, boolean retryable) {
}
