package repit.repit_api_server.global.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;
import repit.repit_api_server.global.client.AiServerApi;
import repit.repit_api_server.global.common.ApiResponse;
import repit.repit_api_server.global.config.HttpInterfaceConfig;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 인터셉터가 실제로 클라이언트에 물려 있는지 확인한다.
 * 단위 테스트만으로는 설정에서 빠뜨려도 드러나지 않고, 응답 본문을 로그가 먼저 읽어버려
 * 호출한 쪽이 빈손이 되는 사고도 여기서만 잡힌다.
 */
class ExternalApiLoggingWiringTest {

    private static final String RESPONSE_BODY = "{\"success\":true,\"data\":{\"questionId\":7}}";

    private HttpServer aiServer;
    private Logger interceptorLogger;
    private ListAppender<ILoggingEvent> capturedLogs;

    @BeforeEach
    void 가짜_분석_서버를_띄운다() throws Exception {
        aiServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        aiServer.createContext("/api/v1/ai/createQuestion", exchange -> {
            byte[] body = RESPONSE_BODY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        aiServer.start();

        interceptorLogger = (Logger) LoggerFactory.getLogger(ExternalApiLoggingInterceptor.class);
        capturedLogs = new ListAppender<>();
        capturedLogs.start();
        interceptorLogger.addAppender(capturedLogs);
    }

    @AfterEach
    void 정리한다() {
        interceptorLogger.detachAppender(capturedLogs);
        aiServer.stop(0);
    }

    @Test
    void 설정으로_만든_클라이언트가_호출하면_외부_호출이_로그에_남고_응답도_그대로_돌아온다() {
        contextRunner().run(context -> {
            AiServerApi aiServerApi = context.getBean(AiServerApi.class);

            ApiResponse<?> response = aiServerApi.createQuestion();

            // 로그가 본문을 먼저 읽어도 호출한 쪽은 같은 본문을 받아야 한다.
            assertThat(response.getSuccess()).isTrue();
            assertThat(response.getData()).isNotNull();

            assertThat(messages()).anySatisfy(message -> assertThat(message)
                    .contains("ext --> [AI] GET")
                    .contains("/api/v1/ai/createQuestion"));
            assertThat(messages()).anySatisfy(message -> assertThat(message)
                    .contains("ext <-- [AI] GET")
                    .contains("200 OK")
                    .contains("\"questionId\":7"));
        });
    }

    @Test
    void 로깅을_끄면_외부_호출도_남기지_않는다() {
        contextRunner()
                .withPropertyValues("app.http-logging.enabled=false")
                .run(context -> {
                    context.getBean(AiServerApi.class).createQuestion();

                    assertThat(capturedLogs.list).isEmpty();
                });
    }

    private ApplicationContextRunner contextRunner() {
        String baseUrl = "http://127.0.0.1:" + aiServer.getAddress().getPort();
        return new ApplicationContextRunner()
                .withBean("restClientBuilder", RestClient.Builder.class, RestClient::builder)
                .withUserConfiguration(HttpInterfaceConfig.class)
                .withPropertyValues(
                        "ai-server.base-url=" + baseUrl,
                        "auth-server.base-url=" + baseUrl,
                        "chat-server.base-url=" + baseUrl
                );
    }

    private List<String> messages() {
        return capturedLogs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
