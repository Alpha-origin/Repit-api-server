package repit.repit_api_server.global.exception;

import lombok.Getter;
import org.springframework.http.HttpStatusCode;

@Getter
public class ExternalApiException extends RuntimeException {

    private final HttpStatusCode statusCode;

    public ExternalApiException(String message, HttpStatusCode statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }
}
