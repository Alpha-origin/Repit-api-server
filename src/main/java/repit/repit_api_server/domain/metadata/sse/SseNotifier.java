package repit.repit_api_server.domain.metadata.sse;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import repit.repit_api_server.global.logging.ClientDisconnect;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * 구독으로 이벤트를 흘려보낸다.
 *
 * <p>분석 완료와 면접 준비 완료는 서로 다른 도메인에서 나지만 같은 구독으로 나가야 한다.
 * 웹은 분석 jobId 하나로 구독하고, 그 연결이 면접에 입장할 수 있게 될 때까지 이어진다.
 * 두 도메인이 각자 보내면 중복을 막는 규약이 갈라지므로 여기 한곳에 모은다.
 */
@Component
@RequiredArgsConstructor
public class SseNotifier {

    private static final Logger log = LoggerFactory.getLogger(SseNotifier.class);

    /** 분석이 끝나 원질문이 나왔다. 아직 면접에 들어갈 수는 없어 구독은 이어진다. */
    public static final String QUESTION_GENERATED = "question-generated";
    /** 분석이 실패했다. 이 흐름으로는 면접을 열 수 없다. */
    public static final String QUESTION_GENERATION_FAILED = "question-generation-failed";
    /** 질문이 확정되고 채팅 서버에 면접이 열렸다. 웹은 이때 입장한다. */
    public static final String INTERVIEW_READY = "interview-ready";
    /** 채팅 서버에 면접을 열지 못했다. 질문은 준비됐지만 입장할 수 없다. */
    public static final String INTERVIEW_PREPARATION_FAILED = "interview-preparation-failed";

    private static final String STATUS_SUCCEEDED = "succeeded";

    private final SseEmitterRepository sseEmitterRepository;

    /**
     * 분석 결과를 흘려보낸다.
     *
     * <p>성공은 구독을 닫지 않는다. 원질문이 나왔다는 것은 아직 중간 단계라, 웹은 이 이벤트를
     * 받고 면접을 만들어 시작을 요청한다. 그 뒤 질문 재작성까지 끝나야 입장할 수 있다.
     */
    public void sendAnalysisResult(String jobId, String status, Object payload) {
        if (STATUS_SUCCEEDED.equalsIgnoreCase(status)) {
            send(jobId, QUESTION_GENERATED, payload);
            return;
        }
        sendFinal(jobId, QUESTION_GENERATION_FAILED, payload);
    }

    /** 흐름이 이어지는 이벤트. 보낸 뒤에도 구독을 남긴다. */
    public void send(String jobId, String eventName, Object payload) {
        emit(jobId, eventName, payload, false);
    }

    /** 흐름이 끝나는 이벤트. 보내고 구독을 닫는다. */
    public void sendFinal(String jobId, String eventName, Object payload) {
        emit(jobId, eventName, payload, true);
    }

    private void emit(String jobId, String eventName, Object payload, boolean last) {
        if (jobId == null) {
            return;
        }
        SseSubscription subscription = sseEmitterRepository.get(jobId);
        if (subscription == null) {
            // 아직 아무도 구독하지 않았다. 상태는 DB에 있으니 구독이 붙을 때 되짚어 보낸다.
            return;
        }
        // 권리를 차지한 쪽만 보낸다. 콜백과 구독 시점 되짚기가 겹쳐도 이벤트는 한 번만 나간다.
        if (!subscription.claim(eventName)) {
            return;
        }

        try {
            subscription.send(SseEmitter.event().name(eventName).data(payload));
            if (last) {
                sseEmitterRepository.remove(jobId, subscription);
                subscription.complete();
            }
        } catch (IOException | RuntimeException e) {
            discard(jobId, subscription, eventName, e);
        }
    }

    /**
     * 흘려보내지 못한 구독을 정리한다.
     *
     * <p>보내다 실패한 연결에는 다음 이벤트도 나가지 않는다. 남겨두면 뒤 단계가 이미 죽은
     * 연결에 쓰려다 같은 실패를 되풀이하므로, 여기서 걷어낸다. 상태는 DB에 남아 있어
     * 클라이언트가 다시 붙으면 그때 되짚어 나간다.
     */
    private void discard(String jobId, SseSubscription subscription, String eventName, Exception e) {
        sseEmitterRepository.remove(jobId, subscription);

        if (ClientDisconnect.isClientGone(e)) {
            // 새로고침이나 탭 닫기로도 나는 정상적인 일이다. 스택트레이스까지 남기면 손댈 곳
            // 없는 예순 줄이 로그를 메워, 정작 손봐야 할 실패가 묻힌다.
            log.info("구독이 끊겨 {}를 흘려보내지 못했습니다. jobId={}, 이유={}",
                    eventName, jobId, ClientDisconnect.rootCauseMessage(e));
            SseEmitters.completeQuietly(subscription);
            return;
        }
        log.warn("{}를 구독에 흘려보내지 못했습니다. jobId={}", eventName, jobId, e);
        SseEmitters.completeWithErrorQuietly(subscription, e);
    }

}
