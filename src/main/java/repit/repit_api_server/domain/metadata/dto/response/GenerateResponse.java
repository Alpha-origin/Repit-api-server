package repit.repit_api_server.domain.metadata.dto.response;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 분석 서버의 접수 응답(202). /generate, /profile, /questions/cycle이 같은 모양이다. */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GenerateResponse {
    @JsonAlias("job_id")
    private String jobId;
    private String status;
    private String message;
}
