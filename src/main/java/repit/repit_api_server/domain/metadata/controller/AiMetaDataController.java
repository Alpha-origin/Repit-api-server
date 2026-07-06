package repit.repit_api_server.domain.metadata.controller;

import lombok.RequiredArgsConstructor;
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
import repit.repit_api_server.global.common.ApiResponse;

import java.io.IOException;
import java.util.Arrays;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/ai")
public class AiMetaDataController {
    private static final long SSE_TIMEOUT = 10 * 60 * 1000L; // 10분

    private final MetaService metaService;
    private final AiMetaDataService aiMetaDataService;
    private final SseEmitterRepository sseEmitterRepository;

    private final AiServerClient aiServerClient;

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
                .callback_url("https://wildcat-startle-rope.ngrok-free.dev/api/v1/ai/callback")
                .build();

        GenerateResponse response = aiServerClient.generate(request);
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
                .callback_url("https://wildcat-startle-rope.ngrok-free.dev/api/v1/ai/callback")
                .build();

        GenerateResponse response = aiServerClient.generateMock(request);
        return ResponseEntity.ok(response);
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
                .build();

        sendCompletionEvent(request.getJob_id(), response);

        return ApiResponse.success(response);
    }

    private void sendCompletionEvent(String jobId, CallbackSuccessResponse response) {
        SseEmitter emitter = sseEmitterRepository.get(jobId);
        if (emitter == null) {
            return;
        }

        try {
            emitter.send(SseEmitter.event()
                    .name("question-generated")
                    .data(response));
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        } finally {
            sseEmitterRepository.remove(jobId);
        }
    }

    @GetMapping
    public ApiResponse<ResultResponse> getResult(
            @RequestParam String jobId
    ) {
        return ApiResponse.success(aiMetaDataService.getResult(jobId));
    }
}
