package repit.repit_api_server.domain.metadata.controller;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import repit.repit_api_server.global.exception.ExternalApiException;
import repit.repit_api_server.global.response.UserResponse;

import java.io.IOException;
import java.util.Arrays;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/ai")
public class AiMetaDataController {

    private static final Logger log = LoggerFactory.getLogger(AiMetaDataController.class);

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

        // 콜백이 구독보다 먼저 도착했을 수 있다. 그때는 붙는 즉시 결과를 돌려주고 끝낸다.
        // 되짚어주지 않으면 이미 끝난 작업을 구독한 클라이언트는 아무것도 받지 못한 채 타임아웃까지
        // 매달려 있고, EventSource가 그때마다 다시 붙어 재연결만 반복한다.
        CallbackSuccessResponse finished = aiMetaDataService.findFinished(jobId);
        if (finished != null) {
            sendCompletionEvent(emitter, jobId, finished);
            return emitter;
        }

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

    /**
     * 이후 채팅 서버 요청에서 jobId를 서버가 직접 찾을 수 있도록 소유자를 기록해둔다.
     *
     * <p>소유자를 남기지 못하면 이 분석 결과는 사용자로 되찾을 수 없어 면접 질문 재작성이
     * 예전 결과를 집어 든다. 그래서 실패를 조용히 넘기지 않고 반드시 로그로 남긴다.
     *
     * <p>다만 이 시점에는 분석 서버가 이미 작업을 접수한 뒤다. 소유자 기록이 실패했다고 요청
     * 전체를 실패시키면 클라이언트가 jobId를 받지 못해 결과를 영영 조회할 수 없게 되므로,
     * 기록 실패는 예외로 번지지 않게 막는다.
     */
    private void registerJobOwner(String authorization, GenerateResponse response) {
        if (response == null || response.getJob_id() == null) {
            // jobId가 없으면 구독도 조회도 할 수 없다. 성공으로 돌려주면 원인을 찾을 수 없다.
            log.error("분석 서버 응답에 job_id가 없습니다. status={}, message={}",
                    response == null ? null : response.getStatus(),
                    response == null ? null : response.getMessage());
            throw new ExternalApiException("분석 서버가 작업 번호를 돌려주지 않았습니다.", null, null);
        }

        try {
            UserResponse user = authServerClient.getUser(authorization);
            if (user == null || user.getId() == null) {
                log.error("분석 작업의 소유자를 확인하지 못했습니다. jobId={}", response.getJob_id());
                return;
            }
            aiMetaDataService.registerJob(response.getJob_id(), user.getId());
        } catch (RuntimeException e) {
            log.error("분석 작업의 소유자를 기록하지 못했습니다. jobId={}", response.getJob_id(), e);
        }
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
            // 아직 아무도 구독하지 않았다. 결과는 DB에 있으니 구독이 붙을 때 되짚어 보낸다.
            return;
        }
        sendCompletionEvent(emitter, jobId, response);
    }

    private void sendCompletionEvent(SseEmitter emitter, String jobId, CallbackSuccessResponse response) {
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

    // 분석 결과 조회. 면접 질문은 재작성이 끝나는 시점에 채팅 서버로 직접 넘어간다.
    @GetMapping
    public ApiResponse<ResultResponse> getResult(
            @RequestParam String jobId
    ) {
        return ApiResponse.success(aiMetaDataService.getResult(jobId));
    }
}
