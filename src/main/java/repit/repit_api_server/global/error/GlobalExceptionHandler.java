package repit.repit_api_server.global.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
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
        return asJson(e.getStatus()).body(new ErrorResponse(e.getMessage()));
    }

    @ExceptionHandler(ExternalApiException.class)
    public ResponseEntity<ErrorResponse> handleExternalApiException(ExternalApiException e) {
        HttpStatusCode status = e.getStatusCode() != null ? e.getStatusCode() : HttpStatus.BAD_GATEWAY;
        // 재시도까지 간 실패는 ExternalApiExecutor가 이미 남겼다. 여기서는 그 실패가 어떤 응답으로 나갔는지만 잇는다.
        log.warn("외부 서버 호출 실패를 {}로 응답합니다. message={}", status, e.getMessage());
        return asJson(status).body(new ErrorResponse(e.getMessage()));
    }

    /**
     * 오류 본문의 타입을 못박는다.
     *
     * <p>정하지 않으면 스프링이 요청의 Accept와 협상해 쓸 타입을 고른다. 구독을 여는 요청은
     * {@code text/event-stream}만 받겠다고 하므로 JSON을 고를 수 없어 협상이 깨지고, 그
     * 예외는 예외 핸들러 안에서 난 것이라 다시 처리되지 못한 채 본문 없는 500으로 나간다.
     * 그러면 토큰이 만료된 것인지 남의 것을 본 것인지 클라이언트가 알 수 없다.
     *
     * <p>타입이 정해져 있으면 협상을 건너뛰고 그대로 쓴다. 오류 본문은 원래도 JSON이었으므로
     * 다른 요청에서 나가는 응답은 달라지지 않는다.
     */
    private ResponseEntity.BodyBuilder asJson(HttpStatusCode status) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON);
    }

    /**
     * 표준 스프링 MVC 예외가 어떤 응답으로 나갔는지 남긴다.
     *
     * <p>{@link ResponseEntityExceptionHandler}가 처리하는 예외 — 깨진 JSON, 빠진 파라미터,
     * 맞지 않는 메서드나 미디어 타입 — 는 본문 없는 4xx로 조용히 나간다. 그대로 두면 로그에는
     * 상태 코드 한 줄만 남아, 어느 API가 무엇 때문에 튕겼는지 되짚을 수가 없다. 요청을 보낸
     * 쪽도 빈 본문만 받으므로 서버 로그가 유일한 단서다.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e, @Nullable Object body,
                                                            HttpHeaders headers, HttpStatusCode statusCode,
                                                            WebRequest request) {
        if (statusCode.is5xxServerError()) {
            log.error("요청을 처리하지 못했습니다. status={}, 원인={}", statusCode, e.getMessage(), e);
        } else {
            // 잘못 온 요청이라 스택트레이스는 붙이지 않는다. 어느 예외였는지와 메시지면 충분하다.
            log.warn("요청을 받아들이지 못했습니다. status={}, 예외={}, 원인={}",
                    statusCode, e.getClass().getSimpleName(), e.getMessage());
        }
        return super.handleExceptionInternal(e, body, headers, statusCode, request);
    }

    // ResponseEntityExceptionHandler가 이미 처리하는 표준 스프링 MVC 예외(검증 실패, 잘못된 요청 등)는
    // 더 구체적인 타입으로 매칭되어 이 메서드보다 우선 처리되므로 그대로 원래 상태코드를 유지한다.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception e) {
        log.error("처리되지 않은 예외가 발생했습니다.", e);
        return asJson(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("서버 내부 오류가 발생했습니다."));
    }
}
