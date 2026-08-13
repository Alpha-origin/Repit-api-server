package repit.repit_api_server.global.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import repit.repit_api_server.global.client.AiServerApi;
import repit.repit_api_server.global.client.AuthServerApi;
import repit.repit_api_server.global.client.ChatServerApi;

import static org.assertj.core.api.Assertions.assertThat;

class HttpInterfaceConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean("restClientBuilder", org.springframework.web.client.RestClient.Builder.class,
                    org.springframework.web.client.RestClient::builder)
            .withUserConfiguration(HttpInterfaceConfig.class)
            .withPropertyValues(
                    "ai-server.base-url=http://localhost:8080",
                    "auth-server.base-url=http://localhost:8081",
                    "chat-server.base-url=http://localhost:8082"
            );

    @Test
    void 기본_타임아웃_프로퍼티로_세_클라이언트_빈이_정상_생성된다() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AiServerApi.class);
            assertThat(context).hasSingleBean(AuthServerApi.class);
            assertThat(context).hasSingleBean(ChatServerApi.class);
        });
    }

    @Test
    void 타임아웃_프로퍼티를_직접_지정해도_정상_생성된다() {
        contextRunner
                .withPropertyValues(
                        "external-api.connect-timeout=1s",
                        "external-api.read-timeout=2s"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(AiServerApi.class);
                    assertThat(context).hasSingleBean(AuthServerApi.class);
                    assertThat(context).hasSingleBean(ChatServerApi.class);
                });
    }
}
