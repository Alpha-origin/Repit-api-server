package repit.repit_api_server.domain.metadata.dto.request;

import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class SendDataRequest {
    private String[] gitUrls;
}
