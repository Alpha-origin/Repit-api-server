package repit.repit_api_server.global.error;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import repit.repit_api_server.global.exception.ExternalApiException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    @RestController
    static class TestController {
        @GetMapping("/unexpected")
        public String unexpected() {
            throw new IllegalStateException("내부 시크릿 정보가 담긴 예외 메시지");
        }

        @GetMapping("/external")
        public String external() {
            throw new ExternalApiException("인증 실패", HttpStatus.UNAUTHORIZED, new RuntimeException("cause"));
        }

        // 문자열이 아니라 타입이 있는 본문이어야 JSON 변환을 거치고, 깨진 본문에서 400이 난다.
        @PostMapping("/echo")
        public Long echo(@RequestBody EchoRequest request) {
            return request.interviewId();
        }
    }

    record EchoRequest(Long interviewId) {
    }

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new TestController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    private Logger handlerLogger;
    private ListAppender<ILoggingEvent> capturedLogs;

    @BeforeEach
    void 로그_수집기를_붙인다() {
        handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        capturedLogs = new ListAppender<>();
        capturedLogs.start();
        handlerLogger.addAppender(capturedLogs);
    }

    @AfterEach
    void 로그_수집기를_뗀다() {
        handlerLogger.detachAppender(capturedLogs);
    }

    @Test
    void 처리되지_않은_예외는_500과_안전한_메시지로_변환된다() throws Exception {
        mockMvc.perform(get("/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("서버 내부 오류가 발생했습니다."));
    }

    @Test
    void ExternalApiException은_보존된_상태코드로_응답한다() throws Exception {
        mockMvc.perform(get("/external"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("인증 실패"));
    }

    @Test
    void 스프링_MVC가_아는_예외는_fallback에_가려지지_않고_원래_상태코드를_유지한다() throws Exception {
        mockMvc.perform(post("/unexpected"))
                .andExpect(status().isMethodNotAllowed());
    }

    /**
     * 표준 MVC 예외는 본문 없는 4xx로 나간다. 원인을 남기지 않으면 로그에는 상태 코드만 남아
     * 어느 API가 무엇 때문에 튕겼는지 되짚을 수 없다.
     */
    @Test
    void 스프링_MVC가_아는_예외도_원인이_로그에_남는다() throws Exception {
        mockMvc.perform(post("/unexpected"))
                .andExpect(status().isMethodNotAllowed());

        assertThat(capturedLogs.list).anySatisfy(event -> {
            assertThat(event.getFormattedMessage())
                    .contains("405")
                    .contains("HttpRequestMethodNotSupportedException");
            assertThat(event.getLevel().levelStr).isEqualTo("WARN");
        });
    }

    @Test
    void 깨진_요청_본문도_원인이_로그에_남는다() throws Exception {
        mockMvc.perform(post("/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{깨진 JSON"))
                .andExpect(status().isBadRequest());

        assertThat(capturedLogs.list).anySatisfy(event -> {
            assertThat(event.getFormattedMessage())
                    .contains("400")
                    .contains("HttpMessageNotReadableException");
            assertThat(event.getLevel().levelStr).isEqualTo("WARN");
        });
    }
}
