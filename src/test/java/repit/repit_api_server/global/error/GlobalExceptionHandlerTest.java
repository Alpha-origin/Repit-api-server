package repit.repit_api_server.global.error;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import repit.repit_api_server.global.exception.ExternalApiException;

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
    }

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new TestController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

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
}
