package repit.repit_api_server.domain.company;

/**
 * 분석 서버 기업 계약(00 §6-1)의 길이·개수 상한. 분석 서버가 같은 규칙으로 422를 내므로,
 * 패키지를 가져올 때와 스냅샷을 만들 때 같은 값으로 막는다.
 */
public final class CompanyLimits {

    public static final int DISPLAY_NAME = 30;
    public static final int FACT_TITLE = 20;
    public static final int FACT_CONTENT = 200;
    public static final int SIGNALS_PER_FACT = 3;
    public static final int SIGNAL = 100;
    public static final int INTERVIEWER_BRIEF = 600;
    public static final int FACTS = 20;

    private CompanyLimits() {
    }

    /** 분석 서버(Python)가 세는 글자 수와 맞춘다. String.length()는 이모지 하나를 둘로 센다. */
    public static int length(String value) {
        return value.codePointCount(0, value.length());
    }
}
