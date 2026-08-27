package repit.repit_api_server.global.logging;

import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

/**
 * 클라이언트가 먼저 떠나서 난 실패인지 가린다.
 *
 * <p>새로고침이나 탭 닫기로도 나는 정상적인 일이다. 이것까지 실패로 남기면 손댈 곳이 없는
 * 줄들이 로그를 메워, 정작 손봐야 할 실패가 묻힌다.
 *
 * <p>SSE 구독은 분석부터 면접 준비까지를 한 연결로 덮으면서 몇 분씩 열려 있다. 그동안
 * 사용자가 떠나는 일은 예외가 아니라 흔한 끝맺음이라, 이 판정이 필요한 곳이 늘었다.
 * 흘려보내는 쪽과 남기는 쪽이 같은 기준을 쓰도록 여기 한곳에 둔다.
 */
public final class ClientDisconnect {

    // 예외가 몇 겹으로 싸여 있어도 원인 사슬은 이 깊이까지만 따라간다.
    private static final int MAX_CAUSE_DEPTH = 10;

    private ClientDisconnect() {
    }

    public static boolean isClientGone(Throwable failure) {
        Throwable cause = failure;
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
    public static String rootCauseMessage(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; cause.getCause() != null && cause.getCause() != cause && depth < MAX_CAUSE_DEPTH; depth++) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }
}
