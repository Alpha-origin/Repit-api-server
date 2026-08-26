package repit.repit_api_server.domain.metadata.sse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 구독을 닫는 일은 어디서 하든 실패할 수 있다. 그 처리를 한곳에 모은다. */
public final class SseEmitters {

    private static final Logger log = LoggerFactory.getLogger(SseEmitters.class);

    private SseEmitters() {
    }

    /**
     * 구독을 닫되, 닫지 못해도 넘어간다.
     *
     * <p>이미 에러로 끝난 연결에 닫기를 걸면 톰캣이 거부한다. 에러 처리가 끝난 AsyncContext를
     * 다시 쓰는 일을 막기 위한 것이라, 우리가 피해 갈 수 있는 종류가 아니다.
     *
     * <p>닫기는 이미 끝난 연결을 정리하려는 최선의 시도일 뿐이다. 여기서 예외가 새어나가면
     * 부르는 쪽이 하던 일이 통째로 멈춘다 — 하트비트라면 그 뒤 순서의 구독들이 ping을 잃는다.
     */
    public static void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (RuntimeException e) {
            log.debug("이미 끝난 구독이라 닫지 못했습니다. 원인: {}", e.toString());
        }
    }

    /** 닫기와 같은 이유로, 오류로 끝내는 것도 실패할 수 있다. */
    public static void completeWithErrorQuietly(SseEmitter emitter, Throwable failure) {
        try {
            emitter.completeWithError(failure);
        } catch (RuntimeException e) {
            log.debug("이미 끝난 구독이라 오류로 닫지 못했습니다. 원인: {}", e.toString());
        }
    }
}
