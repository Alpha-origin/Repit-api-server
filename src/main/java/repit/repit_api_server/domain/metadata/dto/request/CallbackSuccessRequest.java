package repit.repit_api_server.domain.metadata.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * /generate 콜백. 성공/실패가 같은 경로로 들어오고 status로 갈린다.
 * 실패면 result가 없고 error에 사유가 실린다.
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class CallbackSuccessRequest {
    private String jobId;

    // "succeeded" 또는 "failed"
    private String status;

    // 성공 콜백에만 존재
    private Object result;

    // 실패 콜백에만 존재
    private Error error;

    @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class Error {
        // 422(잘못된 PDF), 403(private 저장소), 500(내부 오류)
        private Integer status_code;
        private String message;
    }
}
