package repit.repit_api_server.domain.metadata.sse;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 구독 하나. 어떤 이벤트를 이미 흘려보냈는지 함께 들고 다닌다.
 *
 * <p>한 구독이 분석 완료와 면접 준비 완료를 차례로 받는다. 앞의 이벤트는 흐름이 이어지므로
 * 보낸 뒤에도 연결이 남고, 그래서 "먼저 걷어낸 쪽만 보낸다"는 규약만으로는 중복을 막지 못한다.
 * 걷어내지 않으니 콜백과 구독 시점 되짚기가 같은 이벤트를 두 번 보낼 수 있고, 웹은 그것을
 * 받을 때마다 면접을 새로 만든다.
 *
 * <p>그래서 보내는 권리를 이벤트 이름 단위로 차지한다. 연결마다 따로 두는 것은, 다시 붙은
 * 구독은 앞 단계를 받은 적이 없어 되짚어줘야 하기 때문이다.
 */
public class SseSubscription extends SseEmitter {

    private final Set<String> sentEvents = ConcurrentHashMap.newKeySet();

    public SseSubscription(Long timeout) {
        super(timeout);
    }

    /** 이 이벤트를 보낼 권리를 차지한다. 처음 차지한 쪽만 true를 받는다. */
    public boolean claim(String eventName) {
        return sentEvents.add(eventName);
    }
}
