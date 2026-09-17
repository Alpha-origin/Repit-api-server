package repit.repit_api_server.domain.userdata.feedback.service;

/** 다른 패키지의 테스트가 패키지 전용 분류를 볼 수 있게 여는 창구. */
public final class FeedbackDispatchServiceClassifier {

    private FeedbackDispatchServiceClassifier() {
    }

    public static String kindOf(RuntimeException e) {
        return FeedbackDispatchService.classify(e).name();
    }
}
