package repit.repit_api_server.global.client;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import repit.repit_api_server.domain.userdata.feedback.dto.request.FeedbackSoloRequest;
import repit.repit_api_server.domain.userdata.feedback.service.FeedbackDispatchServiceClassifier;
import repit.repit_api_server.global.config.HttpInterfaceConfig;
import repit.repit_api_server.global.exception.ExternalApiException;
import repit.repit_api_server.global.logging.HttpLoggingProperties;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 실제 분석 서버 클라이언트가 던지는 예외로 "받지 않음"과 "받았는지 모름"이 갈리는지 본다.
 *
 * <p>분류는 원인 체인의 예외 타입에 기대는데, 그 타입은 쓰는 HTTP 클라이언트(지금은 Reactor Netty)가 정한다.
 * 목으로 만든 예외로는 클라이언트가 바뀌었을 때 분류가 조용히 틀어지는 것을 잡지 못한다. 연결 거부를
 * "모름"으로 보면 채점이 10분씩 늦어지고, 읽기 타임아웃을 "받지 않음"으로 보면 같은 면접이 두 번 채점된다.
 */
class AiServerFailureClassificationTest {

    @Test
    void 연결이_거부되면_받지_않은_것이다() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        ExternalApiException failure = requestTo("http://127.0.0.1:" + closedPort, "1s");

        assertThat(FeedbackDispatchServiceClassifier.kindOf(failure)).isEqualTo("NOT_ACCEPTED");
    }

    @Test
    void 연결된_뒤_응답이_없으면_받았는지_모르는_것이다() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        try (ServerSocket silent = new ServerSocket(0)) {
            // 요청을 받기만 하고 답하지 않는 분석 서버.
            Thread server = new Thread(() -> {
                try (Socket ignored = silent.accept()) {
                    done.await(5, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                }
            });
            server.start();

            ExternalApiException failure = requestTo("http://127.0.0.1:" + silent.getLocalPort(), "500ms");
            done.countDown();

            assertThat(FeedbackDispatchServiceClassifier.kindOf(failure)).isEqualTo("UNCONFIRMED");
        }
    }

    private static ExternalApiException requestTo(String baseUrl, String readTimeout) {
        HttpInterfaceConfig config = new HttpInterfaceConfig(new HttpLoggingProperties(false, false, 2000, List.of()));
        AiServerClient client = new AiServerClient(
                config.aiServerApi(RestClient.builder(), baseUrl, "1s", readTimeout), new ExternalApiExecutor());
        return catchThrowableOfType(ExternalApiException.class,
                () -> client.requestSoloFeedback(FeedbackSoloRequest.builder().build()));
    }
}
