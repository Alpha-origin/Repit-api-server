package repit.repit_api_server.domain.metadata.sse;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 하트비트는 두 가지를 한다. 놀고 있는 연결을 프록시가 끊지 못하게 붙잡아두고,
 * 이미 끊긴 연결은 콜백이 도착하기 전에 걷어낸다.
 */
class SseHeartbeatTest {

    private final SseEmitterRepository repository = new SseEmitterRepository();
    private final SseHeartbeat heartbeat = new SseHeartbeat(repository);

    @Test
    void 살아있는_구독에는_빈_줄을_흘리고_그대로_둔다() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        repository.save("job-1", emitter);

        heartbeat.ping();

        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(repository.get("job-1")).isSameAs(emitter);
    }

    /** 여기서 걷어내지 않으면 콜백이 도착했을 때 죽은 연결에 완료 이벤트를 쓰다 잃는다. */
    @Test
    void 끊긴_구독은_걷어내고_닫는다() throws IOException {
        SseEmitter gone = mock(SseEmitter.class);
        doThrow(new IOException("Broken pipe")).when(gone).send(any(SseEmitter.SseEventBuilder.class));
        repository.save("job-2", gone);

        heartbeat.ping();

        assertThat(repository.get("job-2")).isNull();
        verify(gone).complete();
    }

    /** 이미 끝난 구독에 쓰면 IllegalStateException이 난다. 이것도 걷어낼 대상이다. */
    @Test
    void 이미_끝난_구독도_걷어낸다() throws IOException {
        SseEmitter finished = mock(SseEmitter.class);
        doThrow(new IllegalStateException("ResponseBodyEmitter has already completed"))
                .when(finished).send(any(SseEmitter.SseEventBuilder.class));
        repository.save("job-3", finished);

        heartbeat.ping();

        assertThat(repository.get("job-3")).isNull();
    }

    /** 한 구독이 끊겼다고 나머지 구독까지 못 받게 되면 안 된다. */
    @Test
    void 끊긴_구독이_있어도_나머지는_계속_흐른다() throws IOException {
        SseEmitter gone = mock(SseEmitter.class);
        doThrow(new IOException("Broken pipe")).when(gone).send(any(SseEmitter.SseEventBuilder.class));
        SseEmitter alive = mock(SseEmitter.class);
        repository.save("job-4", gone);
        repository.save("job-5", alive);

        heartbeat.ping();

        assertThat(repository.get("job-4")).isNull();
        assertThat(repository.get("job-5")).isSameAs(alive);
        verify(alive).send(any(SseEmitter.SseEventBuilder.class));
    }
}
