package repit.repit_api_server.domain.gitRepo.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import repit.repit_api_server.domain.gitRepo.dto.request.AuthRequest;
import repit.repit_api_server.domain.gitRepo.dto.response.AuthResponse;
import repit.repit_api_server.domain.gitRepo.service.GitUrlAuthService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/gitUrl")
public class GitUrlController {
    private final GitUrlAuthService gitUrlService;

    @PostMapping("/forward")
    public ResponseEntity<AuthResponse> forward(@RequestBody AuthRequest request) {
        return ResponseEntity.ok(
                gitUrlService.getGitUrl(request)
        );
    }
}
