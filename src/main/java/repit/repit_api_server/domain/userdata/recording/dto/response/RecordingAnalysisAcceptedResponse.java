package repit.repit_api_server.domain.userdata.recording.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class RecordingAnalysisAcceptedResponse {
    private String jobId;
    private String status;
    private String message;
}
