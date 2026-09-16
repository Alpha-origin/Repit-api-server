package repit.repit_api_server.domain.userdata.interview;

/**
 * N:1 면접의 면접관 인원 규칙.
 *
 * <p>면접을 만드는 자리와 질문을 준비하는 자리가 같은 상한을 봐야 한다. 한쪽만 알고 있으면
 * 생성은 통과했는데 준비가 분석 서버에서 거절당하거나, 반대로 생성에서 막힌 구성이 준비
 * 쪽에서는 멀쩡해 보이는 어긋남이 생긴다. 근거도 두 군데로 갈린다. 그래서 여기 하나로 둔다.
 */
public final class MultiInterviewPanel {

    /**
     * 기술 외 면접관 수 하한.
     *
     * <p>기술 면접관 한 명은 따로 반드시 있어야 한다. 원질문을 다시 쓰는 몫이 그 자리라 대신할
     * 면접관이 없다. 거기에 다른 직책이 최소 한 명은 붙어야 면접관이 교대하고, 그 교대가 곧
     * N:1이 1:1과 갈리는 지점이다. 기술 면접관만 남으면 1:1을 N:1이라 부르는 것과 다르지 않다.
     */
    public static final int MIN_OTHER_COUNT = 1;

    /**
     * 기술 외 면접관 수 상한.
     *
     * <p>분석 서버 {@code /questions/tailor/multi}가 otherPersonas를 이만큼까지만 받는다. 더 보내면
     * 422로 거부당하는데, 그 실패는 면접 시작을 누른 뒤에야 드러난다. 여기서 막아 생성 시점에
     * 알린다. 상한을 올리려면 분석 서버 계약이 먼저 넓어져야 한다.
     */
    public static final int MAX_OTHER_COUNT = 3;

    /** 기술 면접관 한 명을 더한 면접 전체 인원. 사용자에게 보이는 수는 이쪽이라 안내에 쓴다. */
    public static final int MIN_TOTAL_COUNT = MIN_OTHER_COUNT + 1;
    public static final int MAX_TOTAL_COUNT = MAX_OTHER_COUNT + 1;

    private MultiInterviewPanel() {
    }

    /** 기술 외 면접관 수가 열어둔 범위 안인지. 기술 면접관은 세지 않는다. */
    public static boolean isOtherCountAllowed(int otherCount) {
        return otherCount >= MIN_OTHER_COUNT && otherCount <= MAX_OTHER_COUNT;
    }
}
