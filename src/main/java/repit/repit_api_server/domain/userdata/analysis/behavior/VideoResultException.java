package repit.repit_api_server.domain.userdata.analysis.behavior;

/**
 * 영상 분석 결과 문서가 계약에 맞지 않는다.
 *
 * <p>메시지에는 어긋난 위치(JSON 경로)와 규칙 이름만 담는다. 받은 값은 넣지 않는다 — 이 메시지가 따로 남기는 기록의
 * detail과 로그에 그대로 실린다.
 */
public class VideoResultException extends RuntimeException {

    private final String path;
    private final String rule;

    public VideoResultException(String path, String rule) {
        super(path + ": " + rule);
        this.path = path;
        this.rule = rule;
    }

    public String path() {
        return path;
    }

    public String rule() {
        return rule;
    }
}
