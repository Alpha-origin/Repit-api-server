package repit.repit_api_server.domain.metadata.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import repit.repit_api_server.domain.metadata.dto.request.CallbackSuccessRequest;
import repit.repit_api_server.domain.metadata.dto.request.GenerateRequest;
import repit.repit_api_server.domain.metadata.dto.request.MetaDataRequest;
import repit.repit_api_server.domain.metadata.dto.response.CallbackSuccessResponse;
import repit.repit_api_server.domain.metadata.dto.response.GenerateResponse;
import repit.repit_api_server.domain.metadata.dto.response.MetaDataResponse;
import repit.repit_api_server.domain.metadata.dto.response.ResultResponse;
import repit.repit_api_server.domain.metadata.service.AiMetaDataService;
import repit.repit_api_server.domain.metadata.service.MetaService;
import repit.repit_api_server.domain.metadata.sse.SseEmitterRepository;
import repit.repit_api_server.global.client.AiServerClient;
import repit.repit_api_server.global.client.AuthServerClient;
import repit.repit_api_server.global.common.ApiResponse;
import repit.repit_api_server.global.response.UserResponse;

import java.io.IOException;
import java.util.Arrays;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/ai")
public class AiMetaDataController {
    private static final long SSE_TIMEOUT = 10 * 60 * 1000L; // 10분

    @Value("${app.callback-base-url}")
    private String callbackBaseUrl;

    private final MetaService metaService;
    private final AiMetaDataService aiMetaDataService;
    private final SseEmitterRepository sseEmitterRepository;

    private final AiServerClient aiServerClient;
    private final AuthServerClient authServerClient;

    @GetMapping("/subscribe/{jobId}")
    public SseEmitter subscribe(@PathVariable String jobId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        sseEmitterRepository.save(jobId, emitter);

        emitter.onCompletion(() -> sseEmitterRepository.remove(jobId));
        emitter.onTimeout(() -> sseEmitterRepository.remove(jobId));
        emitter.onError((e) -> sseEmitterRepository.remove(jobId));

        try {
            emitter.send(SseEmitter.event()
                    .name("connect")
                    .data("connected"));
        } catch (IOException e) {
            sseEmitterRepository.remove(jobId);
        }

        return emitter;
    }

    @PostMapping("/sendMetaData")
    public ResponseEntity<MetaDataResponse> sendMetaData(@RequestHeader("Authorization") String authorization) {
        MetaDataResponse forRequest = metaService.getMetaData(authorization);
        MetaDataRequest request = MetaDataRequest.builder()
                .gitUrls(forRequest.getGitUrls())
                .fileUrl(forRequest.getFileUrl())
                .build();

        MetaDataResponse response = aiServerClient.sendMetaData(authorization, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/generate")
    public ResponseEntity<GenerateResponse> generate(
            @RequestHeader("Authorization") String authorization
    ) {
        MetaDataResponse forRequest = metaService.getMetaData(authorization);
        GenerateRequest request = GenerateRequest.builder()
                .portfolio_url(forRequest.getFileUrl())
                .github_urls(forRequest.getGitUrls())
                .callback_url(callbackBaseUrl + "/api/v1/ai/callback")
                .build();

        GenerateResponse response = aiServerClient.generate(request);
        registerJobOwner(authorization, response);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/generate-mock")
    public ResponseEntity<GenerateResponse> generateMock(
            @RequestHeader("Authorization") String authorization
    ) {
        MetaDataResponse forRequest = metaService.getMetaData(authorization);
        GenerateRequest request = GenerateRequest.builder()
                .portfolio_url(forRequest.getFileUrl())
                .github_urls(forRequest.getGitUrls())
                .callback_url(callbackBaseUrl + "/api/v1/ai/callback")
                .build();

        GenerateResponse response = aiServerClient.generateMock(request);
        registerJobOwner(authorization, response);
        return ResponseEntity.ok(response);
    }

    // 이후 채팅 서버 요청에서 jobId를 서버가 직접 찾을 수 있도록 소유자를 기록해둔다.
    private void registerJobOwner(String authorization, GenerateResponse response) {
        UserResponse user = authServerClient.getUser(authorization);
        if (user == null) {
            return;
        }
        aiMetaDataService.registerJob(response.getJob_id(), user.getId());
    }

    @PostMapping("/callback")
    public ApiResponse<CallbackSuccessResponse> callback(
            @RequestBody CallbackSuccessRequest request
            ) {
        aiMetaDataService.saveResult(request);
        CallbackSuccessResponse response = CallbackSuccessResponse.builder()
                .job_id(request.getJob_id())
                .status(request.getStatus())
                .result(request.getResult())
                .error(request.getError())
                .build();

        sendCompletionEvent(request.getJob_id(), response);

        return ApiResponse.success(response);
    }

    private void sendCompletionEvent(String jobId, CallbackSuccessResponse response) {
        SseEmitter emitter = sseEmitterRepository.get(jobId);
        if (emitter == null) {
            return;
        }

        String eventName = "succeeded".equalsIgnoreCase(response.getStatus())
                ? "question-generated"
                : "question-generation-failed";

        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(response));
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        } finally {
            sseEmitterRepository.remove(jobId);
        }
    }

    /**
     * 채팅 서버가 면접에 쓸 질문을 가져가는 경로.
     * 질문 재작성이 끝나 있으면 재작성된 본문이 담겨 나간다.
     * 한 분석 결과로 여러 면접을 여는 경우 interviewId를 함께 주면 그 면접의 재작성본으로 정확히 맞춘다.
     */
    @GetMapping
    public ApiResponse<ResultResponse> getResult(
            @RequestParam String jobId,
            @RequestParam(required = false) Long interviewId
    ) {
        return ApiResponse.success(aiMetaDataService.getResult(jobId, interviewId));
    }
}
