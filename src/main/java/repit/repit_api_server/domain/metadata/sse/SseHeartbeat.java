package repit.repit_api_server.domain.metadata.sse;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * 붙어 있는 구독에 주기적으로 빈 줄을 흘린다.
 *
 * <p>분석은 끝날 때까지 아무 이벤트도 내보내지 않는다. 그동안 연결에 바이트가 하나도 흐르지
 * 않으면 중간의 프록시나 터널이 놀고 있는 연결로 보고 조용히 끊는다. 클라이언트는 다시 붙지만,
 * 그 사이에 콜백이 도착하면 이미 죽은 연결에 완료 이벤트를 쓰다 broken pipe로 잃는다.
 *
 * <p>끊긴 연결을 일찍 알아채는 효과도 같이 얻는다. SSE는 다음 쓰기를 시도할 때까지 상대가
 * 떠난 줄 모르므로, 아무것도 보내지 않으면 콜백이 도착하는 순간에야 알게 된다.
 */
@Component
@RequiredArgsConstructor
public class SseHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(SseHeartbeat.class);

    private final SseEmitterRepository sseEmitterRepository;

    @Scheduled(fixedDelayString = "${app.sse.heartbeat-interval-ms:15000}")
    public void ping() {
        sseEmitterRepository.forEach(this::ping);
    }

    private void ping(String jobId, SseEmitter emitter) {
        try {
            // 주석 줄이라 클라이언트의 이벤트 처리에는 걸리지 않는다. 연결을 살려두는 용도다.
            emitter.send(SseEmitter.event().comment("ping"));
        } catch (IOException | IllegalStateException e) {
            // 이미 떠난 구독이다. 여기서 걷어내야 콜백이 도착했을 때 죽은 연결에 쓰지 않는다.
            if (sseEmitterRepository.remove(jobId, emitter)) {
                log.debug("응답하지 않는 구독을 걷어냈습니다. jobId={}", jobId);
                emitter.complete();
            }
        }
    }
}
