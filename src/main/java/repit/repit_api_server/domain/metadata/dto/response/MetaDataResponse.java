package repit.repit_api_server.domain.metadata.dto.response;

import lombok.*;

import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetaDataResponse {

    private List<String> gitUrls;

    private String fileUrl;

}
