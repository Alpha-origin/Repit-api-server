package repit.repit_api_server.global.error;

/**
 * 오류 응답의 {@code code} 값. 상태 코드만으로는 웹이 안내를 가를 수 없는 경우에만 쓴다(기업 데이터셋 00 §8).
 *
 * <p>외부 입력 형식 오류와 내부 데이터 오류를 같은 값으로 묶지 않는다. 사용자가 할 수 있는 일이 다르다.
 */
public final class ErrorCodes {

    // 고른 기업이 없거나 숨겨졌다. 설정 초안을 두고 기업을 다시 고르게 한다.
    public static final String COMPANY_UNAVAILABLE = "COMPANY_UNAVAILABLE";
    // 공개 기업인데 저장된 데이터로 실행 스냅샷을 만들 수 없다. 데이터를 고칠 일이라 사용자가 고칠 수 없다.
    public static final String COMPANY_CONTEXT_UNAVAILABLE = "COMPANY_CONTEXT_UNAVAILABLE";
    // 같은 Idempotency-Key로 다른 설정의 면접을 만들려 했다.
    public static final String IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED";
    // 면접 생성 요청의 헤더·ID 형식이 틀렸다.
    public static final String INVALID_INTERVIEW_REQUEST = "INVALID_INTERVIEW_REQUEST";

    private ErrorCodes() {
    }
}
