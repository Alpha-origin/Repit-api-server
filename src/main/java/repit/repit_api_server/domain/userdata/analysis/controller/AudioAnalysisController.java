package repit.repit_api_server.domain.userdata.analysis.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import repit.repit_api_server.domain.userdata.analysis.dto.request.AudioAnalysisCallbackRequest;
import repit.repit_api_server.domain.userdata.analysis.dto.response.AudioAnalysisResponse;
import repit.repit_api_server.domain.userdata.analysis.service.AudioAnalysisService;
import repit.repit_api_server.global.auth.AuthUser;
import repit.repit_api_server.global.common.ApiResponse;

@RestController
@RequiredArgsConstructor
public class AudioAnalysisController {

    private final AudioAnalysisService audioAnalysisService;

    // 분석 서버 전용 콜백. 2xx가 늦거나 실패하면 결과가 폐기되므로 그대로 저장만 하고 응답한다.
    @PostMapping("/api/analyses/audio/callback")
    public ResponseEntity<Void> audioCallback(@RequestBody AudioAnalysisCallbackRequest request) {
        audioAnalysisService.handleCallback(request);
        return ResponseEntity.ok().build();
    }

    /** 면접의 음성 분석 상태와 답변별 음성 지표. */
    @GetMapping("/api/interviews/{interviewId}/audio-analysis")
    public ApiResponse<AudioAnalysisResponse> get(@AuthenticationPrincipal AuthUser authUser,
                                                  @PathVariable Long interviewId) {
        return ApiResponse.success(audioAnalysisService.get(authUser.id(), interviewId));
    }

    /** 음성 분석을 다시 요청한다. 면접당 2번까지, 결과가 없거나 다시 하면 나아질 실패가 있는 답변만 다시 보낸다. */
    @PostMapping("/api/interviews/{interviewId}/audio-analysis/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<AudioAnalysisResponse> retry(@AuthenticationPrincipal AuthUser authUser,
                                                    @PathVariable Long interviewId) {
        return ApiResponse.success(audioAnalysisService.retry(authUser.id(), interviewId));
    }
}
