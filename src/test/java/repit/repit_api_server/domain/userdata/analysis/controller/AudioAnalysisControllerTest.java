package repit.repit_api_server.domain.userdata.analysis.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import repit.repit_api_server.domain.userdata.analysis.dto.response.AudioAnalysisResponse;
import repit.repit_api_server.domain.userdata.analysis.dto.response.AudioAnalysisResponse.Status;
import repit.repit_api_server.domain.userdata.analysis.service.AudioAnalysisService;
import repit.repit_api_server.global.auth.AuthUser;
import repit.repit_api_server.global.error.GlobalExceptionHandler;
import repit.repit_api_server.global.response.UserResponse;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 웹이 읽는 응답 모양과 분석 서버가 부르는 콜백 경로. 이름이 어긋나면 웹은 지표를 못 그리고 콜백은 404로 버려진다. */
@ExtendWith(MockitoExtension.class)
class AudioAnalysisControllerTest {

    @Mock
    private AudioAnalysisService audioAnalysisService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AudioAnalysisController(audioAnalysisService))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        UserResponse user = new UserResponse();
        ReflectionTestUtils.setField(user, "id", 7L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AuthUser(user, "Bearer token"), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 면접의_음성_분석을_답변별로_내려준다() throws Exception {
        when(audioAnalysisService.get(7L, 42L)).thenReturn(new AudioAnalysisResponse(Status.PARTIAL, List.of(
                new AudioAnalysisResponse.Answer("1203", "2207", "301", "audio-1", Status.PARTIAL, 120000L,
                        Map.of("status", "ready"), Map.of("status", "partial"), null)), true));

        mockMvc.perform(get("/api/interviews/42/audio-analysis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PARTIAL"))
                .andExpect(jsonPath("$.data.answers[0].questionId").value("1203"))
                .andExpect(jsonPath("$.data.answers[0].recordingId").value("301"))
                .andExpect(jsonPath("$.data.answers[0].requestId").value("audio-1"))
                .andExpect(jsonPath("$.data.answers[0].status").value("PARTIAL"))
                .andExpect(jsonPath("$.data.answers[0].timing.status").value("ready"))
                .andExpect(jsonPath("$.data.canRetry").value(true));
    }

    @Test
    void 음성_분석을_다시_요청하면_202로_받는다() throws Exception {
        when(audioAnalysisService.retry(7L, 42L)).thenReturn(new AudioAnalysisResponse(Status.PENDING, List.of(), false));

        mockMvc.perform(post("/api/interviews/42/audio-analysis/retry"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.canRetry").value(false));
    }

    @Test
    void 콜백_경로는_그대로다() throws Exception {
        mockMvc.perform(post("/api/analyses/audio/callback").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        verify(audioAnalysisService).handleCallback(any());
    }
}
