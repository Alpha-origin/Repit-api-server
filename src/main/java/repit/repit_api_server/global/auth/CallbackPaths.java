package repit.repit_api_server.global.auth;

import java.util.Set;

/**
 * 서버 간 콜백 경로. 사용자 토큰이 아니라 {@link InternalCallbackAuthInterceptor}의
 * 내부 인증값이 지키는 자리다.
 *
 * <p>한 곳에 모아둔다. 시큐리티에서 열어주는 목록과 내부 인증을 거는 목록이 따로 있으면
 * 한쪽에만 경로를 추가하는 순간 아무도 지키지 않는 콜백이 생긴다 — 면접 기록을 통째로
 * 갈아치우는 요청까지 누구나 보낼 수 있게 된다.
 */
public final class CallbackPaths {

    /** 분석 서버가 결과를 보내오는 자리와, 채팅 서버가 면접 기록을 넘기는 자리. */
    public static final String[] ALL = {
            // 분석 서버 콜백
            "/api/v1/ai/callback",
            "/api/v1/ai/profile/callback",
            "/api/v1/ai/question-cycle/callback",
            "/api/questions/tailor/callback",
            "/api/questions/tailor/multi/callback",
            "/api/feedbacks/callback",
            "/api/analyses/audio/callback",
            "/api/analyses/video/callback",
            // 채팅 서버가 면접 기록을 넘기는 자리. 질문·답변을 지우고 다시 넣은 뒤
            // 채점까지 이어지므로 인증 없이 열어두면 남의 면접을 통째로 갈아치울 수 있다.
            "/api/interviews/result"
    };

    /**
     * 내부 인증값을 설정하지 않았어도 열어 두지 않는 자리. 부르는 쪽이 처음부터 헤더를 보내기로 하고 연 경로라
     * 단계적 도입이 필요 없다.
     *
     * <p>영상 분석 콜백은 어긋난 본문까지 원문으로 따로 남긴다. 인증 없이 열어 두면 누구나 반복 호출로 임의의
     * 본문을 DB에 쌓을 수 있다. 막혀서 받지 못한 결과는 작업 조회로 되찾는다.
     */
    public static final Set<String> ALWAYS_AUTHENTICATED = Set.of("/api/analyses/video/callback");

    private CallbackPaths() {
    }
}
