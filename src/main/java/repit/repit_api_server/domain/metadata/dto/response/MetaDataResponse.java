package repit.repit_api_server.domain.metadata.dto.response;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetaDataResponse {

    private String[] gitUrls;

    private String fileUrl;

}
