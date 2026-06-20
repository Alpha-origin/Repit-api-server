package repit.repit_api_server.domain.metadata.dto.request;

import io.swagger.v3.core.util.Json;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class CallbackSuccessRequest {
    private String job_id;

    private String status;

    @NotNull
    private Object result;
}
