package repit.repit_api_server.global.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 클라이언트에게 사유를 그대로 보여줘도 되는 업무 예외.
 * 범용 예외 핸들러의 500 응답과 달리 의도한 상태코드와 메시지가 그대로 나간다.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final HttpStatus status;

    public BusinessException(String message, HttpStatus status) {
        super(message);
        this.status = status;
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
