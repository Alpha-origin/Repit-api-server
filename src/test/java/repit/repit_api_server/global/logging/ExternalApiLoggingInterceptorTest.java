package repit.repit_api_server.global.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalApiLoggingInterceptorTest {

    private final ExternalApiLoggingInterceptor interceptor =
            new ExternalApiLoggingInterceptor("AI", HttpLoggingProperties.defaults());

    private Logger interceptorLogger;
    private ListAppender<ILoggingEvent> capturedLogs;

    @BeforeEach
    void 로그_수집기를_붙인다() {
        interceptorLogger = (Logger) LoggerFactory.getLogger(ExternalApiLoggingInterceptor.class);
        capturedLogs = new ListAppender<>();
        capturedLogs.start();
        interceptorLogger.addAppender(capturedLogs);
    }

    @AfterEach
    void 로그_수집기를_뗀다() {
        interceptorLogger.detachAppender(capturedLogs);
    }

    @Test
    void 나가는_요청과_돌아온_응답이_서버_이름과_함께_남는다() throws Exception {
        MockClientHttpRequest request = jsonRequest();
        ClientHttpRequestExecution execution = (req, body) ->
                jsonResponse(HttpStatus.ACCEPTED, "{\"jobId\":\"job-1\"}");

        interceptor.intercept(request, "{\"interview_id\":3}".getBytes(StandardCharsets.UTF_8), execution);

        assertThat(messages()).anySatisfy(message -> assertThat(message)
                .contains("ext --> [AI] POST http://ai-server/feedback/solo")
                .contains("{\"interview_id\":3}"));
        assertThat(messages()).anySatisfy(message -> assertThat(message)
                .contains("ext <-- [AI] POST http://ai-server/feedback/solo")
                .contains("202 ACCEPTED")
                .contains("{\"jobId\":\"job-1\"}"));
    }

    /** 영상 분석 작업 조회 응답에는 행동 관찰 수치가, 요청에는 서명된 원본 주소가 실린다. 본문은 남기지 않는다. */
    @Test
    void 영상_분석_요청과_작업_조회의_본문은_남기지_않는다() throws Exception {
        MockClientHttpRequest job = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://ai-server/analysis/video/jobs/job-1"));
        ClientHttpResponse response = jsonResponse(HttpStatus.OK,
                "{\"result\":{\"result\":{\"analysis\":{\"data\":{\"metrics\":{\"headFacingRatio\":{\"value\":0.8}}}}}}}");

        ClientHttpResponse returned = interceptor.intercept(job, new byte[0], (req, body) -> response);
        interceptor.intercept(new MockClientHttpRequest(HttpMethod.POST, URI.create("http://ai-server/analysis/video")),
                "{\"video\":{\"videoId\":\"501\"}}".getBytes(StandardCharsets.UTF_8),
                (req, body) -> jsonResponse(HttpStatus.ACCEPTED, "{\"jobId\":\"job-1\"}"));

        assertThat(messages()).hasSize(4).allSatisfy(message -> assertThat(message)
                .contains("body=생략").doesNotContain("headFacingRatio").doesNotContain("videoId").doesNotContain("job-1\""));
        // 로그가 본문을 읽지 않았으니 호출한 쪽이 그대로 읽는다.
        assertThat(new String(returned.getBody().readAllBytes(), StandardCharsets.UTF_8)).contains("headFacingRatio");
    }

    @Test
    void 응답_본문을_로그로_읽어도_호출한_쪽이_같은_본문을_다시_읽는다() throws Exception {
        // 실제 클라이언트는 BufferingClientHttpRequestFactory로 감싸 두 번 읽을 수 있다.
        ClientHttpResponse buffered = jsonResponse(HttpStatus.OK, "{\"value\":1}");
        ClientHttpRequestExecution execution = (req, body) -> buffered;

        ClientHttpResponse response = interceptor.intercept(jsonRequest(), new byte[0], execution);

        assertThat(response).isSameAs(buffered);
    }

    @Test
    void 서버_오류_응답은_error로_남는다() throws Exception {
        ClientHttpRequestExecution execution = (req, body) ->
                jsonResponse(HttpStatus.INTERNAL_SERVER_ERROR, "{\"message\":\"boom\"}");

        interceptor.intercept(jsonRequest(), new byte[0], execution);

        assertThat(capturedLogs.list).anySatisfy(event -> {
            assertThat(event.getFormattedMessage()).contains("500 INTERNAL_SERVER_ERROR");
            assertThat(event.getLevel().levelStr).isEqualTo("ERROR");
        });
    }

    @Test
    void 응답이_오지_않으면_원인을_남기고_예외를_그대로_올린다() {
        ClientHttpRequestExecution execution = (req, body) -> {
            throw new IOException("connect timed out");
        };

        assertThatThrownBy(() -> interceptor.intercept(jsonRequest(), new byte[0], execution))
                .isInstanceOf(IOException.class);

        assertThat(capturedLogs.list).anySatisfy(event -> {
            assertThat(event.getFormattedMessage()).contains("응답 없음").contains("connect timed out");
            assertThat(event.getLevel().levelStr).isEqualTo("ERROR");
        });
    }

    @Test
    void 인증_헤더는_값을_가린_채로만_남는다() throws Exception {
        MockClientHttpRequest request = jsonRequest();
        request.getHeaders().set(HttpHeaders.AUTHORIZATION, "Bearer eyJhbGciOiJIUzI1NiJ9");

        interceptor.intercept(request, new byte[0], (req, body) -> jsonResponse(HttpStatus.OK, "{}"));

        assertThat(messages()).anySatisfy(message -> assertThat(message)
                .contains("auth=Bearer ***")
                .doesNotContain("eyJhbGciOiJIUzI1NiJ9"));
    }

    private MockClientHttpRequest jsonRequest() {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST, URI.create("http://ai-server/feedback/solo"));
        request.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return request;
    }

    private MockClientHttpResponse jsonResponse(HttpStatus status, String body) {
        MockClientHttpResponse response = new MockClientHttpResponse(body.getBytes(StandardCharsets.UTF_8), status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return response;
    }

    private List<String> messages() {
        return capturedLogs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
