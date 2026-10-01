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
import repit.repit_api_server.domain.userdata.analysis.dto.response.VideoAnalysisResponse;
import repit.repit_api_server.domain.userdata.analysis.service.VideoAnalysisCallbackHandler;
import repit.repit_api_server.domain.userdata.analysis.service.VideoAnalysisService;
import repit.repit_api_server.global.auth.AuthUser;
import repit.repit_api_server.global.common.ApiResponse;

@RestController
@RequiredArgsConstructor
public class VideoAnalysisController {

    private final VideoAnalysisService videoAnalysisService;
    private final VideoAnalysisCallbackHandler callbackHandler;

    /**
     * 분석 서버 전용 콜백. 본문을 원문 그대로 받는다 — 모양이 어긋나도 400으로 버리지 않고 따로 남기기 위해서다.
     * 검증을 통과하지 못한 원문도 남기는 데 성공하면 200으로 답한다.
     */
    @PostMapping("/api/analyses/video/callback")
    public ResponseEntity<Void> callback(@RequestBody(required = false) String body) {
        callbackHandler.handle(body);
        return ResponseEntity.ok().build();
    }

    /** 면접 화면 녹화의 분석 상태와 보여 줄 결과. */
    @GetMapping("/api/interviews/{interviewId}/video-analysis")
    public ApiResponse<VideoAnalysisResponse> get(@AuthenticationPrincipal AuthUser authUser,
                                                  @PathVariable Long interviewId) {
        return ApiResponse.success(videoAnalysisService.get(authUser.id(), interviewId));
    }

    /** 영상 분석을 다시 요청한다. 영상당 2번까지, 분석이 결과 없이 끝났거나 일부만 나왔을 때만 받는다. */
    @PostMapping("/api/interviews/{interviewId}/video-analysis/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<VideoAnalysisResponse> retry(@AuthenticationPrincipal AuthUser authUser,
                                                    @PathVariable Long interviewId) {
        return ApiResponse.success(videoAnalysisService.retry(authUser.id(), interviewId));
    }
}
