package repit.repit_api_server.domain.metadata.dto.request;

import lombok.*;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetaDataRequest {

    private String gitUrl;

    private String fileUrl;

}
