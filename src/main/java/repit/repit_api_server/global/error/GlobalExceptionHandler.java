package repit.repit_api_server.global.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import repit.repit_api_server.global.exception.ExternalApiException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ExternalApiException.class)
    public ResponseEntity<ErrorResponse> handleExternalApiException(ExternalApiException e) {
        HttpStatusCode status = e.getStatusCode() != null ? e.getStatusCode() : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status).body(new ErrorResponse(e.getMessage()));
    }
}
