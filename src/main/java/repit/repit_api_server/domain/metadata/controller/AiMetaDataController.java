package repit.repit_api_server.domain.metadata.controller;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import repit.repit_api_server.domain.metadata.dto.request.CallbackSuccessRequest;
import repit.repit_api_server.domain.metadata.dto.request.MetaDataRequest;
import repit.repit_api_server.domain.metadata.dto.response.CallbackSuccessResponse;
import repit.repit_api_server.domain.metadata.dto.response.GenerateResponse;
import repit.repit_api_server.domain.metadata.dto.response.MetaDataResponse;
import repit.repit_api_server.domain.metadata.dto.response.ResultResponse;
import repit.repit_api_server.domain.metadata.service.AiMetaDataService;
import repit.repit_api_server.domain.metadata.service.AnalysisLaunchService;
import repit.repit_api_server.domain.metadata.service.MetaService;
import repit.repit_api_server.domain.metadata.sse.SseEmitterRepository;
import repit.repit_api_server.global.client.AiServerClient;
import repit.repit_api_server.global.common.ApiResponse;

import java.io.IOException;

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

    private final MetaService metaService;
    private final AiMetaDataService aiMetaDataService;
    private final SseEmitterRepository sseEmitterRepository;

    private final AiServerClient aiServerClient;
    private final AnalysisLaunchService analysisLaunchService;

    // 응답 타입을 못박아 둔다. 정하지 않으면 협상 결과에 따라 다른 타입으로 나갈 수 있고,
    // 그러면 중간의 프록시가 이벤트 스트림인 줄 모르고 버퍼에 모았다가 한꺼번에 흘려보낸다.
    @GetMapping(value = "/subscribe/{jobId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
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

    // 이미 올려둔 자료로 분석만 다시 요청한다. 자료를 올리는 길과 같은 접수를 거친다.
    @PostMapping("/generate")
    public ResponseEntity<GenerateResponse> generate(
            @RequestHeader("Authorization") String authorization
    ) {
        MetaDataResponse forRequest = metaService.getMetaData(authorization);
        return ResponseEntity.ok(analysisLaunchService.launch(authorization, forRequest));
    }

    @PostMapping("/generate-mock")
    public ResponseEntity<GenerateResponse> generateMock(
            @RequestHeader("Authorization") String authorization
    ) {
        MetaDataResponse forRequest = metaService.getMetaData(authorization);
        return ResponseEntity.ok(analysisLaunchService.launchMock(authorization, forRequest));
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
