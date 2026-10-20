package repit.repit_api_server.domain.userdata.analysis.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import repit.repit_api_server.domain.userdata.analysis.dto.response.VideoAnalysisResponse;
import repit.repit_api_server.domain.userdata.analysis.service.VideoAnalysisCallbackHandler;
import repit.repit_api_server.domain.userdata.analysis.service.VideoAnalysisService;
import repit.repit_api_server.global.auth.AuthUser;
import repit.repit_api_server.global.common.ApiResponse;
import repit.repit_api_server.global.exception.BusinessException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

@RestController
@RequiredArgsConstructor
public class VideoAnalysisController {

    private final VideoAnalysisService videoAnalysisService;
    private final VideoAnalysisCallbackHandler callbackHandler;

    /**
     * 분석 서버 전용 콜백. 본문을 원문 그대로 받는다 — 모양이 어긋나도 400으로 버리지 않고 따로 남기기 위해서다.
     * 검증을 통과하지 못한 원문도 남기는 데 성공하면 200으로 답한다.
     *
     * <p>본문은 내부 인증(인터셉터)을 통과한 뒤에야 여기서 읽는다. UTF-8 1MiB를 넘으면 읽다 말고 413으로 끊고 아무것도 남기지
     * 않는다 — 넘는 원문을 메모리에 다 올리거나 DB에 쌓지 않는다. 길이를 밝히지 않은 본문도 상한까지만 읽는다.
     */
    @PostMapping("/api/analyses/video/callback")
    public ResponseEntity<Void> callback(HttpServletRequest request) throws IOException {
        int limit = VideoAnalysisCallbackHandler.MAX_BODY_BYTES;
        if (request.getContentLengthLong() > limit) {
            throw tooLarge();
        }
        byte[] bytes = request.getInputStream().readNBytes(limit + 1);
        if (bytes.length > limit) {
            throw tooLarge();
        }
        String body;
        try {
            body = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            callbackHandler.keepUndecodable(new String(bytes, StandardCharsets.UTF_8));
            return ResponseEntity.ok().build();
        }
        callbackHandler.handle(body.isEmpty() ? null : body);
        return ResponseEntity.ok().build();
    }

    /** 면접 화면 녹화의 분석 상태와 보여 줄 행동 관찰 결과. 정상 완료되지 않은 면접은 409다. */
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

    private static BusinessException tooLarge() {
        return new BusinessException("콜백 본문은 " + VideoAnalysisCallbackHandler.MAX_BODY_BYTES + "바이트까지 받습니다.",
                HttpStatus.CONTENT_TOO_LARGE);
    }
}
