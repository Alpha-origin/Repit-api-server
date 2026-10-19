package repit.repit_api_server.global.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;

@Getter
public class ErrorResponse {

    private final String message;
    // 웹이 같은 상태 코드 안에서 원인을 가를 때 쓰는 선택 값. 없으면 키 자체를 내보내지 않아 기존 응답은 그대로다.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final String code;

    public ErrorResponse(String message) {
        this(message, null);
    }

    public ErrorResponse(String message, String code) {
        this.message = message;
        this.code = code;
    }
}
