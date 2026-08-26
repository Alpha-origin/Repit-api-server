package repit.repit_api_server.global.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import repit.repit_api_server.global.exception.BusinessException;
import repit.repit_api_server.global.exception.ExternalApiException;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException e) {
        // 상태 코드만으로는 어느 규칙에 걸렸는지 알 수 없어 한 줄 남긴다. 예상된 흐름이라 스택트레이스는 붙이지 않는다.
        log.warn("업무 규칙에 걸려 요청을 중단했습니다. status={}, message={}", e.getStatus(), e.getMessage());
        return ResponseEntity.status(e.getStatus()).body(new ErrorResponse(e.getMessage()));
    }

    @ExceptionHandler(ExternalApiException.class)
    public ResponseEntity<ErrorResponse> handleExternalApiException(ExternalApiException e) {
        HttpStatusCode status = e.getStatusCode() != null ? e.getStatusCode() : HttpStatus.BAD_GATEWAY;
        // 재시도까지 간 실패는 ExternalApiExecutor가 이미 남겼다. 여기서는 그 실패가 어떤 응답으로 나갔는지만 잇는다.
        log.warn("외부 서버 호출 실패를 {}로 응답합니다. message={}", status, e.getMessage());
        return ResponseEntity.status(status).body(new ErrorResponse(e.getMessage()));
    }

    // ResponseEntityExceptionHandler가 이미 처리하는 표준 스프링 MVC 예외(검증 실패, 잘못된 요청 등)는
    // 더 구체적인 타입으로 매칭되어 이 메서드보다 우선 처리되므로 그대로 원래 상태코드를 유지한다.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception e) {
        log.error("처리되지 않은 예외가 발생했습니다.", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("서버 내부 오류가 발생했습니다."));
    }
}
