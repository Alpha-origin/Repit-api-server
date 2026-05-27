package repit.repit_api_server.domain.metadata.dto.response;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GetMetaDataResponse {

    private String gitUrl;

    private String fileUrl;

}
