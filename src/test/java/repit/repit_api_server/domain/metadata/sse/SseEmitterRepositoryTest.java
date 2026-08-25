package repit.repit_api_server.domain.metadata.sse;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 구독을 jobId만 보고 지우면, 뒤늦게 끝난 예전 구독의 정리 콜백이 방금 붙은 구독을 밀어낸다.
 * 그러면 콜백이 도착해도 흘려보낼 곳이 없어 클라이언트는 타임아웃까지 매달린다.
 */
class SseEmitterRepositoryTest {

    private final SseEmitterRepository repository = new SseEmitterRepository();

    @Test
    void 예전_구독의_정리는_새로_붙은_구독을_밀어내지_않는다() {
        SseEmitter old = new SseEmitter();
        SseEmitter fresh = new SseEmitter();

        repository.save("job-1", old);
        repository.save("job-1", fresh);

        assertThat(repository.remove("job-1", old)).isFalse();
        assertThat(repository.get("job-1")).isSameAs(fresh);
    }

    @Test
    void 자기_자신은_걷어낸다() {
        SseEmitter emitter = new SseEmitter();
        repository.save("job-2", emitter);

        assertThat(repository.remove("job-2", emitter)).isTrue();
        assertThat(repository.get("job-2")).isNull();
    }

    // 먼저 걷어낸 쪽만 이벤트를 보내도록, 두 번째 시도는 실패해야 한다.
    @Test
    void 한_번_걷어낸_구독은_다시_차지할_수_없다() {
        SseEmitter emitter = new SseEmitter();
        repository.save("job-3", emitter);

        assertThat(repository.remove("job-3", emitter)).isTrue();
        assertThat(repository.remove("job-3", emitter)).isFalse();
    }

    @Test
    void 겹친_구독은_밀려난_쪽을_돌려준다() {
        SseEmitter old = new SseEmitter();
        SseEmitter fresh = new SseEmitter();

        assertThat(repository.save("job-4", old)).isNull();
        assertThat(repository.save("job-4", fresh)).isSameAs(old);
    }
}
