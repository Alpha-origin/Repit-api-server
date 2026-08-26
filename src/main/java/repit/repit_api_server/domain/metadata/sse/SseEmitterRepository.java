package repit.repit_api_server.domain.metadata.sse;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;

// 메모리에만 사는 구독 보관소다. @Repository를 달면 JPA 예외 번역 프록시가 걸려,
// 여기서 난 IllegalStateException이 엉뚱한 DB 예외로 둔갑해 원인을 가린다.
@Component
public class SseEmitterRepository {
    private final ConcurrentMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    /**
     * 구독을 등록하고, 같은 작업에 이미 붙어 있던 구독이 있으면 그것을 돌려준다.
     * 밀려난 구독은 흘려보낼 곳이 없으니 호출자가 끊어준다.
     */
    public SseEmitter save(String jobId, SseEmitter emitter) {
        return emitters.put(jobId, emitter);
    }

    public SseEmitter get(String jobId) {
        return emitters.get(jobId);
    }

    /**
     * 자기 자신이 등록돼 있을 때만 걷어내고, 걷어냈는지를 돌려준다.
     *
     * <p>jobId만 보고 지우면 뒤늦게 끝난 예전 구독의 정리 콜백이 방금 붙은 구독을 밀어내,
     * 정작 콜백이 도착했을 때 흘려보낼 곳이 사라진다.
     *
     * <p>돌려주는 값은 "이 구독을 내가 차지했다"는 표시이기도 하다. 콜백과 구독 시점 되짚기가
     * 겹쳐도 먼저 걷어낸 쪽만 이벤트를 보내면 같은 연결에 두 번 쓰는 일이 없다.
     */
    public boolean remove(String jobId, SseEmitter emitter) {
        return emitters.remove(jobId, emitter);
    }

    /**
     * 붙어 있는 구독을 하나씩 훑는다. 훑는 동안 다른 구독이 붙거나 걷혀도 안전하다.
     *
     * <p>맵을 밖으로 내보내지 않는 것은, 걷어내는 일이 {@link #remove}의 "자기 자신일 때만"
     * 규약을 거치게 하기 위해서다. 밖에서 jobId만 보고 지우면 방금 붙은 구독이 밀려난다.
     */
    public void forEach(BiConsumer<String, SseEmitter> action) {
        emitters.forEach(action);
    }
}
