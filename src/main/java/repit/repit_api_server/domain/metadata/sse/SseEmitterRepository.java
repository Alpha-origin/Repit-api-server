package repit.repit_api_server.domain.metadata.sse;

import org.springframework.stereotype.Repository;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
public class SseEmitterRepository {
    private final ConcurrentMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    public SseEmitter save(String jobId, SseEmitter emitter) {
        emitters.put(jobId, emitter);
        return emitter;
    }

    public SseEmitter get(String jobId) {
        return emitters.get(jobId);
    }

    public void remove(String jobId) {
        emitters.remove(jobId);
    }
}
