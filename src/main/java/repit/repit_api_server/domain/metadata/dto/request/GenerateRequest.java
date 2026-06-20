package repit.repit_api_server.domain.metadata.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GenerateRequest {
    private String portfolio_url;
    private List<String> github_urls;
    private String callback_url;
}
