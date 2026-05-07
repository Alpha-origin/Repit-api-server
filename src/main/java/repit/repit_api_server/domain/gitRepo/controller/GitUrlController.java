package repit.repit_api_server.domain.gitRepo.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import repit.repit_api_server.domain.gitRepo.dto.request.AuthRequest;
import repit.repit_api_server.domain.gitRepo.dto.response.AuthResponse;
import repit.repit_api_server.domain.gitRepo.service.GitUrlAuthService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/gitUrl")
public class GitUrlController {
    private final GitUrlAuthService gitUrlAuthService;

    @PostMapping("/setGit")
    public ResponseEntity<AuthResponse> setGit(@RequestBody AuthRequest request) {
        return ResponseEntity.ok(
                gitUrlAuthService.setGitUrl(request)
        );
    }
}
