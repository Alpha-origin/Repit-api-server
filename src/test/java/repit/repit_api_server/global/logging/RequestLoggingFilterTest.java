package repit.repit_api_server.global.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.StreamUtils;
import org.springframework.web.util.ContentCachingResponseWrapper;

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
}
