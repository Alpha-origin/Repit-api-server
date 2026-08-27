package repit.repit_api_server.global.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.core.annotation.Order;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockPart;
import org.springframework.util.StreamUtils;
import repit.repit_api_server.global.config.CorsConfig;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestLoggingFilterTest {

    private final RequestLoggingFilter filter = new RequestLoggingFilter(HttpLoggingProperties.defaults());

    private Logger filterLogger;
    private ListAppender<ILoggingEvent> capturedLogs;

    @BeforeEach
    void 로그_수집기를_붙인다() {
        filterLogger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
        capturedLogs = new ListAppender<>();
        capturedLogs.start();
        filterLogger.addAppender(capturedLogs);
    }

    @AfterEach
    void 로그_수집기를_뗀다() {
        filterLogger.detachAppender(capturedLogs);
    }

    @Test
    void 요청과_응답이_메서드_경로_상태코드_소요시간과_함께_남는다() throws Exception {
        MockHttpServletRequest request = jsonRequest("POST", "/api/feedbacks", "{\"interviewId\":3}");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            ((HttpServletResponse) res).setStatus(202);
            res.getWriter().write("{\"success\":true}");
        });

        assertThat(messages()).anySatisfy(message -> assertThat(message)
                .contains("--> POST /api/feedbacks")
                .contains("{\"interviewId\":3}"));
        assertThat(messages()).anySatisfy(message -> assertThat(message)
                .contains("<-- POST /api/feedbacks")
                .contains("202 ACCEPTED")
                .contains("ms)")
                .contains("{\"success\":true}"));
    }

    @Test
    void 쿼리_문자열도_요청_줄에_함께_남는다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/interviews/get");
        request.setQueryString("interviewId=12");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> ((HttpServletResponse) res).setStatus(200));

        assertThat(messages()).anySatisfy(message ->
                assertThat(message).contains("--> GET /api/interviews/get?interviewId=12"));
    }

    @Test
    void 로그를_남긴_뒤에도_컨트롤러가_같은_본문을_읽을_수_있다() throws Exception {
        MockHttpServletRequest request = jsonRequest("POST", "/api/feedbacks", "{\"interviewId\":3}");
        AtomicReference<String> bodyReadByHandler = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
                bodyReadByHandler.set(StreamUtils.copyToString(req.getInputStream(), StandardCharsets.UTF_8)));

        assertThat(bodyReadByHandler.get()).isEqualTo("{\"interviewId\":3}");
    }

    @Test
    void 응답_본문은_로그를_거쳐도_클라이언트에_그대로_전달된다() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(jsonRequest("GET", "/api/question", null), response,
                (req, res) -> res.getWriter().write("{\"success\":true}"));

        assertThat(response.getContentAsString()).isEqualTo("{\"success\":true}");
    }

    @Test
    void 파일_업로드_본문은_메모리에_올리지_않는다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/metaData/dataUpload");
        request.setContentType(MediaType.MULTIPART_FORM_DATA_VALUE);
        request.setContent("아주 큰 파일".getBytes(StandardCharsets.UTF_8));
        AtomicReference<Object> requestSeenByHandler = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> requestSeenByHandler.set(req));

        assertThat(requestSeenByHandler.get()).isNotInstanceOf(CachedBodyHttpServletRequest.class);
    }

    @Test
    void SSE_응답은_감싸지_않아_이벤트가_바로_흘러간다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/ai/subscribe/job-1");
        request.addHeader(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE);
        AtomicReference<Object> responseSeenByHandler = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> responseSeenByHandler.set(res));

        assertThat(responseSeenByHandler.get()).isNotInstanceOf(ContentCachingResponseWrapper.class);
    }

    /**
     * Accept는 클라이언트와 중간 프록시가 정하는 값이라 비어 있거나 바뀐 채 도착할 수 있다.
     * 헤더만 보고 가리면 그때 구독 응답이 캐시에 갇혀 이벤트가 흘러가지 않는다.
     */
    @Test
    void 구독_경로는_Accept가_없어도_감싸지_않는다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/ai/subscribe/job-1");
        AtomicReference<Object> responseSeenByHandler = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> responseSeenByHandler.set(res));

        assertThat(responseSeenByHandler.get()).isNotInstanceOf(ContentCachingResponseWrapper.class);
    }

    @Test
    void 구독_경로는_Accept가_모든_타입이어도_감싸지_않는다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/ai/subscribe/job-1");
        request.addHeader(HttpHeaders.ACCEPT, MediaType.ALL_VALUE);
        AtomicReference<Object> responseSeenByHandler = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> responseSeenByHandler.set(res));

        assertThat(responseSeenByHandler.get()).isNotInstanceOf(ContentCachingResponseWrapper.class);
    }

    /** 구독이 아닌 요청까지 놓치면 본문 로그가 통째로 사라진다. */
    @Test
    void 구독이_아닌_요청의_응답은_그대로_감싼다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/ai?jobId=job-1");
        AtomicReference<Object> responseSeenByHandler = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> responseSeenByHandler.set(res));

        assertThat(responseSeenByHandler.get()).isInstanceOf(ContentCachingResponseWrapper.class);
    }

    @Test
    void 서버_오류는_error로_클라이언트_오류는_warn으로_남는다() throws Exception {
        filter.doFilter(new MockHttpServletRequest("GET", "/api/question"), new MockHttpServletResponse(),
                (req, res) -> ((HttpServletResponse) res).setStatus(500));
        filter.doFilter(new MockHttpServletRequest("GET", "/api/question"), new MockHttpServletResponse(),
                (req, res) -> ((HttpServletResponse) res).setStatus(404));

        assertThat(capturedLogs.list).anySatisfy(event -> {
            assertThat(event.getFormattedMessage()).contains("500 INTERNAL_SERVER_ERROR");
            assertThat(event.getLevel().levelStr).isEqualTo("ERROR");
        });
        assertThat(capturedLogs.list).anySatisfy(event -> {
            assertThat(event.getFormattedMessage()).contains("404 NOT_FOUND");
            assertThat(event.getLevel().levelStr).isEqualTo("WARN");
        });
    }

    @Test
    void 한글_본문도_깨지지_않고_남는다() throws Exception {
        MockHttpServletRequest request = jsonRequest("POST", "/api/persona/save", "{\"name\":\"면접관\"}");
        request.setCharacterEncoding(StandardCharsets.UTF_8);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"message\":\"저장했습니다.\"}");
        });

        assertThat(messages()).anySatisfy(message -> assertThat(message).contains("면접관"));
        assertThat(messages()).anySatisfy(message -> assertThat(message).contains("저장했습니다."));
    }

    @Test
    void 인증_헤더는_값을_가린_채로만_남는다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/question");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer eyJhbGciOiJIUzI1NiJ9");

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
        });

        assertThat(messages()).anySatisfy(message -> assertThat(message)
                .contains("auth=Bearer ***")
                .doesNotContain("eyJhbGciOiJIUzI1NiJ9"));
    }

    @Test
    void 제외한_경로는_아무것도_남기지_않는다() throws Exception {
        filter.doFilter(new MockHttpServletRequest("GET", "/actuator/health"), new MockHttpServletResponse(),
                (req, res) -> {
                });

        assertThat(capturedLogs.list).isEmpty();
    }

    @Test
    void 로깅을_끄면_필터가_아무_일도_하지_않는다() throws Exception {
        RequestLoggingFilter disabledFilter = new RequestLoggingFilter(
                new HttpLoggingProperties(false, true, 2000, null));

        disabledFilter.doFilter(jsonRequest("POST", "/api/feedbacks", "{\"interviewId\":3}"),
                new MockHttpServletResponse(), (req, res) -> {
                });

        assertThat(capturedLogs.list).isEmpty();
    }

    @Test
    void 본문_로깅을_끄면_요청_줄만_남는다() throws Exception {
        RequestLoggingFilter noBodyFilter = new RequestLoggingFilter(
                new HttpLoggingProperties(true, false, 2000, null));

        noBodyFilter.doFilter(jsonRequest("POST", "/api/feedbacks", "{\"interviewId\":3}"),
                new MockHttpServletResponse(), (req, res) -> {
                });

        assertThat(messages()).noneSatisfy(message -> assertThat(message).contains("interviewId"));
        assertThat(messages()).anySatisfy(message -> assertThat(message).contains("--> POST /api/feedbacks"));
    }

    /**
     * CorsFilter는 허용하지 않은 오리진의 요청을 체인에 넘기지 않고 그 자리에서 끊는다.
     * 로깅 필터가 뒤에 서 있으면 프론트 연동이 막힌 요청만 로그에서 통째로 사라진다.
     */
    @Test
    void 로깅_필터가_CORS_필터보다_앞에_선다() {
        int loggingOrder = RequestLoggingFilter.class.getAnnotation(Order.class).value();
        int corsOrder = new CorsConfig().corsFilter().getOrder();

        assertThat(loggingOrder).isLessThan(corsOrder);
    }

    /** 통과한 사전 요청은 곧바로 뒤따르는 본 요청과 겹쳐 흐름만 두 배로 늘린다. */
    @Test
    void 통과한_사전_요청은_남기지_않는다() throws Exception {
        filter.doFilter(preflightRequest("POST"), new MockHttpServletResponse(),
                (req, res) -> ((HttpServletResponse) res).setStatus(200));

        assertThat(capturedLogs.list).isEmpty();
    }

    /**
     * 막힌 사전 요청은 본 요청이 아예 나가지 못한다. 남기지 않으면 서버 쪽에는 흔적이 없고
     * 브라우저에만 CORS 오류가 떠, 허용 목록의 무엇을 고쳐야 할지 알 수 없다.
     */
    @Test
    void 막힌_사전_요청은_오리진과_함께_경고로_남는다() throws Exception {
        filter.doFilter(preflightRequest("POST"), new MockHttpServletResponse(),
                (req, res) -> ((HttpServletResponse) res).setStatus(403));

        assertThat(capturedLogs.list).anySatisfy(event -> {
            assertThat(event.getFormattedMessage())
                    .contains("OPTIONS /api/feedbacks")
                    .contains("403 FORBIDDEN")
                    .contains("origin=https://허용되지-않은.example.com")
                    .contains("요청한 메서드=POST");
            assertThat(event.getLevel().levelStr).isEqualTo("WARN");
        });
    }

    /**
     * 업로드 본문은 메모리에 올릴 수 없어 본문 로그에서 빠진다. 그렇다고 아무것도 남기지 않으면
     * 이 경로만 무엇이 올라왔는지 흔적이 없다. 파일 내용 대신 이름과 크기, 폼 값을 남긴다.
     */
    @Test
    void 업로드_요청은_파일_이름과_크기_폼_값이_남는다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/metaData/dataUpload");
        request.setContentType(MediaType.MULTIPART_FORM_DATA_VALUE);
        request.setParameter("gitUrls", "https://github.com/alpha/repit");
        request.addPart(new MockPart("file", "포트폴리오.pdf", "내용".getBytes(StandardCharsets.UTF_8)));

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
        });

        assertThat(messages()).anySatisfy(message -> assertThat(message)
                .contains("--> POST /api/v1/metaData/dataUpload")
                .contains("gitUrls=https://github.com/alpha/repit")
                .contains("file=포트폴리오.pdf"));
    }

    private MockHttpServletRequest preflightRequest(String requestMethod) {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/feedbacks");
        request.addHeader(HttpHeaders.ORIGIN, "https://허용되지-않은.example.com");
        request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, requestMethod);
        return request;
    }

    private MockHttpServletRequest jsonRequest(String method, String uri, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        if (body != null) {
            request.setContentType(MediaType.APPLICATION_JSON_VALUE);
            request.setContent(body.getBytes(StandardCharsets.UTF_8));
        }
        return request;
    }

    private List<String> messages() {
        return capturedLogs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /**
     * SSE 구독은 분석부터 면접 준비까지를 한 연결로 덮어 몇 분씩 열려 있다. 그동안 사용자가
     * 새로고침하거나 탭을 닫으면 broken pipe로 끝나는데, 이건 사고가 아니라 흔한 끝맺음이다.
     * 실패로 남기면 손볼 것 없는 줄이 로그를 메워 정작 봐야 할 실패가 묻힌다.
     */
    @Test
    void 구독자가_떠나서_끝난_비동기_요청은_실패로_남기지_않는다() throws Exception {
        fireAsyncError(new AsyncRequestNotUsableException(
                "ServletResponse failed to flushBuffer", new IOException("Broken pipe")));

        assertThat(capturedLogs.list).anySatisfy(event -> {
            assertThat(event.getFormattedMessage()).contains("비동기 오류");
            assertThat(event.getFormattedMessage()).contains("Broken pipe");
            assertThat(event.getLevel().levelStr).isEqualTo("INFO");
        });
    }

    /** 클라이언트 사정이 아닌 실패까지 묻으면 손봐야 할 것을 못 본다. */
    @Test
    void 그_밖의_비동기_실패는_경고로_남는다() throws Exception {
        fireAsyncError(new IllegalStateException("직렬화 실패"));

        assertThat(capturedLogs.list).anySatisfy(event -> {
            assertThat(event.getFormattedMessage()).contains("비동기 오류");
            assertThat(event.getLevel().levelStr).isEqualTo("WARN");
        });
    }

    /** 필터를 빠져나간 뒤에 끝나는 요청이라, 완료 로그는 컨테이너가 부르는 리스너에서 난다. */
    private void fireAsyncError(Throwable failure) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/ai/subscribe/job-1");
        request.setAsyncSupported(true);

        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> ((HttpServletRequest) req).startAsync());

        MockAsyncContext asyncContext = (MockAsyncContext) request.getAsyncContext();
        for (AsyncListener listener : asyncContext.getListeners()) {
            listener.onError(new AsyncEvent(asyncContext, failure));
        }
    }
}
