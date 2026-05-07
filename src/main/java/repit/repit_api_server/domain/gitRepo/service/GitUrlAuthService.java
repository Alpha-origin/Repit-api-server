package repit.repit_api_server.domain.gitRepo.service;

import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import repit.repit_api_server.domain.gitRepo.dto.request.AuthRequest;
import repit.repit_api_server.domain.gitRepo.dto.response.AuthResponse;

@Service
public class GitUrlAuthService {

    private final WebClient webClient;

    public GitUrlAuthService(WebClient.Builder builder) {

        this.webClient = builder
                .baseUrl(".com") // auth 서버 url 넣기(필수)
                .build();
    }

    public AuthResponse getGitUrl(AuthRequest request) {
        return webClient.post()
                .uri("/") // auth 서버 세부 경로(엔드포인트 필수)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(AuthResponse.class)
                .block();
    }

}
