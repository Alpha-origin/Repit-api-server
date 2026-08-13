package repit.repit_api_server.global.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import repit.repit_api_server.global.exception.ExternalApiException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalApiExecutorTest {

    private final ExternalApiExecutor executor = new ExternalApiExecutor();

    @Test
    void 성공하면_재시도_없이_바로_반환한다() {
        AtomicInteger callCount = new AtomicInteger();

        String result = executor.execute("테스트", () -> {
            callCount.incrementAndGet();
            return "OK";
        }, status -> "실패", true);

        assertThat(result).isEqualTo("OK");
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    void 멱등_호출은_5xx면_재시도하다가_이후_성공하면_결과를_반환한다() {
        AtomicInteger callCount = new AtomicInteger();

        String result = executor.execute("테스트", () -> {
            if (callCount.incrementAndGet() < 3) {
                throw HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                        HttpHeaders.EMPTY, new byte[0], null);
            }
            return "OK";
        }, status -> "서버 오류", true);

        assertThat(result).isEqualTo("OK");
        assertThat(callCount.get()).isEqualTo(3);
    }

    @Test
    void 멱등_호출은_5xx가_최대_시도_횟수까지_계속되면_ExternalApiException을_던진다() {
        AtomicInteger callCount = new AtomicInteger();

        assertThatThrownBy(() -> executor.execute("테스트", () -> {
            callCount.incrementAndGet();
            throw HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable",
                    HttpHeaders.EMPTY, new byte[0], null);
        }, status -> "서버 오류", true))
                .isInstanceOf(ExternalApiException.class)
                .extracting(e -> ((ExternalApiException) e).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        assertThat(callCount.get()).isEqualTo(3);
    }

    @Test
    void 오류_4xx는_재시도하지_않고_즉시_던진다() {
        AtomicInteger callCount = new AtomicInteger();

        assertThatThrownBy(() -> executor.execute("테스트", () -> {
            callCount.incrementAndGet();
            throw HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized",
                    HttpHeaders.EMPTY, new byte[0], null);
        }, status -> "인증 실패", true))
                .isInstanceOf(ExternalApiException.class)
                .hasMessage("인증 실패");

        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    void 멱등_호출은_연결_실패_시_재시도하다가_결국_ExternalApiException을_던진다() {
        AtomicInteger callCount = new AtomicInteger();

        assertThatThrownBy(() -> executor.execute("테스트", () -> {
            callCount.incrementAndGet();
            throw new ResourceAccessException("connect timed out");
        }, status -> "실패", true))
                .isInstanceOf(ExternalApiException.class)
                .extracting(e -> ((ExternalApiException) e).getStatusCode())
                .isNull();

        assertThat(callCount.get()).isEqualTo(3);
    }

    @Test
    void 비멱등_호출은_5xx여도_재시도하지_않고_한_번만_시도한다() {
        AtomicInteger callCount = new AtomicInteger();

        assertThatThrownBy(() -> executor.execute("테스트", () -> {
            callCount.incrementAndGet();
            throw HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                    HttpHeaders.EMPTY, new byte[0], null);
        }, status -> "서버 오류", false))
                .isInstanceOf(ExternalApiException.class);

        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    void 비멱등_호출은_연결_실패여도_재시도하지_않고_한_번만_시도한다() {
        AtomicInteger callCount = new AtomicInteger();

        assertThatThrownBy(() -> executor.execute("테스트", () -> {
            callCount.incrementAndGet();
            throw new ResourceAccessException("connect timed out");
        }, status -> "실패", false))
                .isInstanceOf(ExternalApiException.class);

        assertThat(callCount.get()).isEqualTo(1);
    }
}
