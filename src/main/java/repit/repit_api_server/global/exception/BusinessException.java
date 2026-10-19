package repit.repit_api_server.global.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 클라이언트에게 사유를 그대로 보여줘도 되는 업무 예외.
 * 범용 예외 핸들러의 500 응답과 달리 의도한 상태코드와 메시지가 그대로 나간다.
 *
 * <p>{@code code}는 선택이다. 같은 상태 코드 안에서 웹이 다르게 안내해야 하는 경우에만 붙인다.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public BusinessException(String message, HttpStatus status) {
        this(message, status, null);
    }

    public BusinessException(String message, HttpStatus status, String code) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static BusinessException notFound(String message) {
        return new BusinessException(message, HttpStatus.NOT_FOUND);
    }

    public static BusinessException unauthorized(String message) {
        return new BusinessException(message, HttpStatus.UNAUTHORIZED);
    }

    public static BusinessException forbidden(String message) {
        return new BusinessException(message, HttpStatus.FORBIDDEN);
    }

    public static BusinessException conflict(String message) {
        return new BusinessException(message, HttpStatus.CONFLICT);
    }

    public static BusinessException unprocessable(String message) {
        return new BusinessException(message, HttpStatus.UNPROCESSABLE_CONTENT);
    }
}
