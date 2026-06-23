package repit.repit_api_server.domain.metadata.dto.request;

import lombok.*;

import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetaDataRequest {

    private List<String> gitUrls;

    private String fileUrl;

}
