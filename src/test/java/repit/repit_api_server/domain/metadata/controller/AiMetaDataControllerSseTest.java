package repit.repit_api_server.domain.metadata.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import repit.repit_api_server.domain.metadata.dto.request.CallbackSuccessRequest;
import repit.repit_api_server.domain.metadata.dto.response.CallbackSuccessResponse;
import repit.repit_api_server.domain.metadata.service.AiMetaDataService;
import repit.repit_api_server.domain.metadata.service.MetaService;
import repit.repit_api_server.domain.metadata.sse.SseEmitterRepository;
import repit.repit_api_server.global.client.AiServerClient;
import repit.repit_api_server.global.client.AuthServerClient;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 완료 이벤트는 분석 서버 콜백이 도착했을 때만 나가야 한다.
 * 구독이 붙는 순간 되짚어주는 경로가 아직 끝나지 않은 작업까지 완료로 흘려보내면,
 * 클라이언트는 분석이 끝난 줄 알고 옛 결과로 면접을 연다.
 */
@ExtendWith(MockitoExtension.class)
class AiMetaDataControllerSseTest {

    @Mock
    private MetaService metaService;
    @Mock
    private AiMetaDataService aiMetaDataService;
    @Mock
    private AiServerClient aiServerClient;
    @Mock
    private AuthServerClient authServerClient;

    private SseEmitterRepository sseEmitterRepository;
    private AiMetaDataController controller;

    @BeforeEach
    void setUp() {
        sseEmitterRepository = new SseEmitterRepository();
        controller = new AiMetaDataController(
                metaService, aiMetaDataService, sseEmitterRepository, aiServerClient, authServerClient);
    }

    @Test
    void 아직_끝나지_않은_작업은_구독만_붙이고_완료_이벤트를_보내지_않는다() {
        when(aiMetaDataService.findFinished("job-1")).thenReturn(null);

        SseEmitter emitter = controller.subscribe("job-1");

        // 구독이 그대로 남아 있다는 건 완료 이벤트가 나가지 않았다는 뜻이다. 보냈다면 걷혔을 것이다.
        assertThat(sseEmitterRepository.get("job-1")).isSameAs(emitter);
    }

    @Test
    void 콜백이_도착하면_구독으로_흘려보내고_걷어낸다() {
        when(aiMetaDataService.findFinished("job-2")).thenReturn(null);
        SseEmitter emitter = controller.subscribe("job-2");
        assertThat(sseEmitterRepository.get("job-2")).isSameAs(emitter);

        CallbackSuccessRequest request = CallbackSuccessRequest.builder()
                .job_id("job-2")
                .status("succeeded")
                .result(Map.of("project_summary", "요약"))
                .build();

        controller.callback(request);

        verify(aiMetaDataService).saveResult(request);
        assertThat(sseEmitterRepository.get("job-2")).isNull();
    }

    // 되짚기는 확인보다 등록이 먼저여야 한다. 순서가 뒤집히면 그 사이에 온 콜백이 버려진다.
    @Test
    void 이미_끝난_작업은_붙는_즉시_되짚고_구독을_남기지_않는다() {
        when(aiMetaDataService.findFinished("job-3")).thenReturn(CallbackSuccessResponse.builder()
                .job_id("job-3")
                .status("succeeded")
                .result(Map.of("project_summary", "요약"))
                .build());

        controller.subscribe("job-3");

        assertThat(sseEmitterRepository.get("job-3")).isNull();
    }

    // 되짚기 경로가 jobId만 보고 정리하면 같은 작업을 보고 있던 다른 구독까지 밀어낸다.
    @Test
    void 되짚기는_같은_작업을_보고_있던_다른_구독을_밀어내지_않는다() {
        SseEmitter other = new SseEmitter();
        sseEmitterRepository.save("job-4", other);

        when(aiMetaDataService.findFinished("job-4")).thenReturn(CallbackSuccessResponse.builder()
                .job_id("job-4")
                .status("succeeded")
                .result(Map.of("project_summary", "요약"))
                .build());

        SseEmitter emitter = controller.subscribe("job-4");

        // 새 구독이 되짚음을 받고 걷혔을 뿐, 맵에 엉뚱한 구독이 남아 있으면 안 된다.
        assertThat(emitter).isNotSameAs(other);
        assertThat(sseEmitterRepository.get("job-4")).isNull();
    }

    /**
     * 구독자가 먼저 떠나면 완료 이벤트를 쓸 때 broken pipe가 난다. 새로고침으로도 나는 일이라
     * 서버 오류로 다루지 않는다. 결과는 이미 DB에 있어 다시 붙으면 되짚어 나간다.
     */
    @Test
    void 이미_떠난_구독은_오류로_닫지_않는다() throws IOException {
        SseEmitter gone = mock(SseEmitter.class);
        doThrow(new AsyncRequestNotUsableException(
                "ServletResponse failed to flushBuffer", new IOException("Broken pipe")))
                .when(gone).send(any(SseEmitter.SseEventBuilder.class));
        sseEmitterRepository.save("job-5", gone);

        controller.callback(CallbackSuccessRequest.builder()
                .job_id("job-5")
                .status("succeeded")
                .result(Map.of("project_summary", "요약"))
                .build());

        assertThat(sseEmitterRepository.get("job-5")).isNull();
        verify(gone).complete();
        verify(gone, never()).completeWithError(any());
    }

    /** 클라이언트 사정이 아닌 실패까지 묻으면 손봐야 할 것을 못 본다. */
    @Test
    void 그_밖의_실패는_오류로_닫는다() throws IOException {
        SseEmitter broken = mock(SseEmitter.class);
        IOException failure = new IOException("직렬화 실패");
        doThrow(failure).when(broken).send(any(SseEmitter.SseEventBuilder.class));
        sseEmitterRepository.save("job-6", broken);

        controller.callback(CallbackSuccessRequest.builder()
                .job_id("job-6")
                .status("succeeded")
                .result(Map.of("project_summary", "요약"))
                .build());

        assertThat(sseEmitterRepository.get("job-6")).isNull();
        verify(broken).completeWithError(failure);
        verify(broken, never()).complete();
    }
}
