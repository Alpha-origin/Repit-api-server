package repit.repit_api_server.domain.metadata.controller;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
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
import java.time.LocalDateTime;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/ai")
public class AiMetaDataController {

    private static final Logger log = LoggerFactory.getLogger(AiMetaDataController.class);

    private static final long SSE_TIMEOUT = 10 * 60 * 1000L; // 10분
    // 끊겼을 때 클라이언트가 다시 붙기까지의 간격. 정해주지 않으면 브라우저 기본값에 맡기게 된다.
    private static final long RECONNECT_DELAY = 3 * 1000L;
    // 예외가 몇 겹으로 싸여 있어도 원인 사슬은 이 깊이까지만 따라간다.
    private static final int MAX_CAUSE_DEPTH = 10;

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

        // 등록을 먼저 하고 결과를 확인한다. 순서를 뒤집으면 확인과 등록 사이에 도착한 콜백이
        // 흘려보낼 구독을 찾지 못해 이벤트를 그대로 버리고, 구독은 타임아웃까지 매달린다.
        SseEmitter previous = sseEmitterRepository.save(jobId, emitter);
        if (previous != null) {
            // 같은 작업을 다시 구독했다. 밀려난 연결에는 이제 아무것도 흐르지 않으니 여기서 끊는다.
            previous.complete();
        }

        // 정리는 자기 자신이 등록돼 있을 때만 한다. jobId만 보고 지우면 뒤늦게 끝난 예전 구독이
        // 방금 붙은 구독을 밀어낸다.
        emitter.onCompletion(() -> sseEmitterRepository.remove(jobId, emitter));
        emitter.onTimeout(() -> sseEmitterRepository.remove(jobId, emitter));
        emitter.onError((e) -> sseEmitterRepository.remove(jobId, emitter));

        // 콜백이 구독보다 먼저 도착했을 수 있다. 그때는 붙는 즉시 결과를 돌려주고 끝낸다.
        // 되짚어주지 않으면 이미 끝난 작업을 구독한 클라이언트는 아무것도 받지 못한 채 타임아웃까지
        // 매달려 있고, EventSource가 그때마다 다시 붙어 재연결만 반복한다.
        CallbackSuccessResponse finished = aiMetaDataService.findFinished(jobId);
        if (finished != null) {
            sendCompletionEvent(emitter, jobId, finished);
            return emitter;
        }

        try {
            emitter.send(SseEmitter.event()
                    .reconnectTime(RECONNECT_DELAY)
                    .name("connect")
                    .data("connected"));
        } catch (IOException e) {
            sseEmitterRepository.remove(jobId, emitter);
            emitter.complete();
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

        // 분석 서버에 넘기기 직전 시각. 이 작업에 남아 있는 결과가 지난 실행의 것인지 가르는 기준이다.
        LocalDateTime requestedAt = LocalDateTime.now();
        GenerateResponse response = aiServerClient.generate(request);
        registerJob(authorization, response, requestedAt);
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

        LocalDateTime requestedAt = LocalDateTime.now();
        GenerateResponse response = aiServerClient.generateMock(request);
        registerJob(authorization, response, requestedAt);
        return ResponseEntity.ok(response);
    }

    /**
     * 이번 분석 실행을 접수한다. 소유자를 기록하고, 같은 jobId에 남아 있던 지난 결과를 걷어낸다.
     *
     * <p>소유자를 남기지 못하면 이 분석 결과는 사용자로 되찾을 수 없어 면접 질문 재작성이
     * 예전 결과를 집어 든다. 그래서 실패를 조용히 넘기지 않고 반드시 로그로 남긴다.
     *
     * <p>소유자를 확인하지 못했더라도 접수 자체는 한다. 지난 결과를 걷어내지 않으면 구독이
     * 붙는 순간 분석 서버의 콜백보다 먼저 옛 결과가 완료 이벤트로 나가기 때문이다.
     *
     * <p>다만 이 시점에는 분석 서버가 이미 작업을 접수한 뒤다. 기록이 실패했다고 요청 전체를
     * 실패시키면 클라이언트가 jobId를 받지 못해 결과를 영영 조회할 수 없게 되므로, 기록 실패는
     * 예외로 번지지 않게 막는다.
     */
    private void registerJob(String authorization, GenerateResponse response, LocalDateTime requestedAt) {
        if (response == null || response.getJob_id() == null) {
            // jobId가 없으면 구독도 조회도 할 수 없다. 성공으로 돌려주면 원인을 찾을 수 없다.
            log.error("분석 서버 응답에 job_id가 없습니다. status={}, message={}",
                    response == null ? null : response.getStatus(),
                    response == null ? null : response.getMessage());
            throw new ExternalApiException("분석 서버가 작업 번호를 돌려주지 않았습니다.", null, null);
        }

        Long userId = null;
        try {
            UserResponse user = authServerClient.getUser(authorization);
            if (user == null || user.getId() == null) {
                log.error("분석 작업의 소유자를 확인하지 못했습니다. jobId={}", response.getJob_id());
            } else {
                userId = user.getId();
            }
        } catch (RuntimeException e) {
            log.error("분석 작업의 소유자를 확인하지 못했습니다. jobId={}", response.getJob_id(), e);
        }

        try {
            aiMetaDataService.registerJob(response.getJob_id(), userId, requestedAt);
        } catch (RuntimeException e) {
            log.error("분석 작업을 접수하지 못했습니다. jobId={}", response.getJob_id(), e);
        }
    }

    /**
     * 분석 서버가 결과를 보내오는 콜백.
     *
     * <p>흘려보내는 것은 요청이 아니라 저장된 결과다. 저장은 요청을 여러 갈래로 걸러내므로
     * — 결과 없는 성공 콜백은 실패로, 이미 성공한 작업에 온 실패 콜백은 무시로 — 요청을 그대로
     * 내보내면 DB에 없는 완료가 구독으로 새어나간다. 클라이언트는 성공을 받아들고 결과를
     * 조회하다 빈손이 된다.
     *
     * <p>저장이 끝난 뒤에야 전송한다. {@code saveResult}가 돌아왔다는 것은 커밋까지 끝났다는
     * 뜻이라, 구독이 받은 완료는 곧바로 조회해도 DB에 있다.
     */
    @PostMapping("/callback")
    public ApiResponse<CallbackSuccessResponse> callback(
            @RequestBody CallbackSuccessRequest request
            ) {
        CallbackSuccessResponse saved = aiMetaDataService.saveResult(request);
        if (saved == null) {
            // 어느 작업의 결과인지 알 수 없어 저장하지 못했다. 흘려보낼 구독도 찾을 수 없다.
            return ApiResponse.success(null);
        }

        sendCompletionEvent(saved.getJob_id(), saved);

        return ApiResponse.success(saved);
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
        // 이 구독을 먼저 걷어낸 쪽만 보낸다. 콜백과 구독 시점 되짚기가 겹쳐도 이벤트는 한 번만 나가고,
        // 이미 끝난 연결에 다시 쓰는 일이 없다.
        if (!sseEmitterRepository.remove(jobId, emitter)) {
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
            if (clientGone(e)) {
                // 구독자가 이미 떠났다. 결과는 DB에 남아 있어, 다시 붙으면 그때 되짚어 나간다.
                log.info("구독이 끊겨 분석 결과를 흘려보내지 못했습니다. jobId={}, 이유={}",
                        jobId, rootCauseMessage(e));
                emitter.complete();
                return;
            }
            log.warn("분석 결과를 구독에 흘려보내지 못했습니다. jobId={}", jobId, e);
            emitter.completeWithError(e);
        }
    }

    /**
     * 클라이언트가 먼저 떠나서 난 실패인지 가린다.
     *
     * <p>새로고침이나 탭 닫기로도 나는 정상적인 일이다. 이것까지 스택트레이스와 함께 남기면
     * 손댈 곳이 없는 예순 줄이 로그를 메워, 정작 손봐야 할 실패가 묻힌다.
     */
    private static boolean clientGone(IOException e) {
        Throwable cause = e;
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++, cause = cause.getCause()) {
            if (cause instanceof AsyncRequestNotUsableException) {
                return true;
            }
            String message = cause.getMessage();
            if (message != null && (message.contains("Broken pipe") || message.contains("Connection reset"))) {
                return true;
            }
        }
        return false;
    }

    /** 껍데기 예외의 메시지는 어디서 끊겼는지를 알려주지 않는다. 실제로 끊긴 이유만 한 줄로 남긴다. */
    private static String rootCauseMessage(Throwable e) {
        Throwable cause = e;
        for (int depth = 0; cause.getCause() != null && cause.getCause() != cause && depth < MAX_CAUSE_DEPTH; depth++) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }

    // 분석 결과 조회. 면접 질문은 재작성이 끝나는 시점에 채팅 서버로 직접 넘어간다.
    @GetMapping
    public ApiResponse<ResultResponse> getResult(
            @RequestParam String jobId
    ) {
        return ApiResponse.success(aiMetaDataService.getResult(jobId));
    }
}
