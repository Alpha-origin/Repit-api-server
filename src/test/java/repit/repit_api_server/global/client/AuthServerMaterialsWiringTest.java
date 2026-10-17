package repit.repit_api_server.global.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;
import repit.repit_api_server.domain.metadata.dto.request.AuthMaterialsRequest;
import repit.repit_api_server.domain.metadata.dto.response.AuthMaterialsResponse;
import repit.repit_api_server.domain.metadata.dto.response.AuthMaterialsResponse.DocumentEntry;
import repit.repit_api_server.domain.metadata.dto.response.AuthMaterialsResponse.SupplementEntry;
import repit.repit_api_server.global.config.HttpInterfaceConfig;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 인증 서버 {@code GET /api/v1/auth/materials}(계약 C-2)의 실제 응답 모양을 설정으로 만든 클라이언트가 읽는지 확인한다.
 * 인증 서버는 응답에 message·null 필드를 함께 보내므로 그대로 흉내 낸다.
 */
class AuthServerMaterialsWiringTest {

    private static final String RESPONSE_BODY = """
            {"success": true, "message": null, "data": {
              "major": "MAJOR_QA_ENGINEER",
              "documents": [{"type": "TEST_PLAN", "fileUrl": "https://s3/plan.pdf", "fileName": "결제-테스트계획.pdf", "pageCount": 12}],
              "gitUrls": ["https://github.com/a/qa"],
              "supplements": [{"title": "결제 QA", "documentIndex": 0, "goal": "중복 결제 막기", "role": null, "decision": null, "outcome": null}],
              "legacyPortfolio": null}}
            """;

    private HttpServer authServer;
    private final AtomicReference<String> receivedAuthorization = new AtomicReference<>();
    private final AtomicReference<String> receivedMethod = new AtomicReference<>();
    private final AtomicReference<String> receivedBody = new AtomicReference<>();

    @BeforeEach
    void 가짜_인증_서버를_띄운다() throws Exception {
        authServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        authServer.createContext("/api/v1/auth/materials", exchange -> {
            receivedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            receivedMethod.set(exchange.getRequestMethod());
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = RESPONSE_BODY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        authServer.start();
    }

    @AfterEach
    void 정리한다() {
        authServer.stop(0);
    }

    @Test
    void 사용자_토큰을_넘기고_직무_문서_GitHub_보완_입력을_읽는다() {
        contextRunner().run(context -> {
            AuthServerClient client = new AuthServerClient(context.getBean(AuthServerApi.class), new ExternalApiExecutor());

            AuthMaterialsResponse response = client.getMaterials("Bearer user-token");

            assertThat(receivedAuthorization.get()).isEqualTo("Bearer user-token");
            assertThat(response.major()).isEqualTo("MAJOR_QA_ENGINEER");
            assertThat(response.documents()).containsExactly(
                    new DocumentEntry("TEST_PLAN", "https://s3/plan.pdf", "결제-테스트계획.pdf", 12));
            assertThat(response.gitUrls()).containsExactly("https://github.com/a/qa");
            assertThat(response.supplements()).containsExactly(
                    new SupplementEntry("결제 QA", 0, "중복 결제 막기", null, null, null));
            assertThat(response.legacyPortfolio()).isNull();
        });
    }

    @Test
    void 저장은_PUT으로_직무_문서_GitHub_보완_입력을_JSON_본문에_담아_보낸다() {
        contextRunner().run(context -> {
            AuthServerClient client = new AuthServerClient(context.getBean(AuthServerApi.class), new ExternalApiExecutor());

            AuthMaterialsResponse response = client.putMaterials("Bearer user-token", new AuthMaterialsRequest(
                    "MAJOR_QA_ENGINEER",
                    List.of(new DocumentEntry("TEST_PLAN", "https://s3/plan.pdf", "결제-테스트계획.pdf", 12)),
                    List.of("https://github.com/a/qa"),
                    List.of(new SupplementEntry("결제 QA", 0, "중복 결제 막기", null, null, null))));

            assertThat(receivedMethod.get()).isEqualTo("PUT");
            assertThat(receivedAuthorization.get()).isEqualTo("Bearer user-token");
            assertThat(receivedBody.get())
                    .contains("\"major\":\"MAJOR_QA_ENGINEER\"")
                    .contains("\"fileName\":\"결제-테스트계획.pdf\"")
                    .contains("\"pageCount\":12")
                    .contains("\"gitUrls\":[\"https://github.com/a/qa\"]")
                    .contains("\"documentIndex\":0");
            assertThat(response.major()).isEqualTo("MAJOR_QA_ENGINEER");
        });
    }

    private ApplicationContextRunner contextRunner() {
        String baseUrl = "http://127.0.0.1:" + authServer.getAddress().getPort();
        return new ApplicationContextRunner()
                .withBean("restClientBuilder", RestClient.Builder.class, RestClient::builder)
                .withUserConfiguration(HttpInterfaceConfig.class)
                .withPropertyValues(
                        "ai-server.base-url=" + baseUrl,
                        "auth-server.base-url=" + baseUrl,
                        "chat-server.base-url=" + baseUrl
                );
    }
}
