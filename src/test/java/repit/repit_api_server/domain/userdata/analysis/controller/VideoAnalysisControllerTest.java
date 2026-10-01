package repit.repit_api_server.domain.userdata.analysis.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import repit.repit_api_server.domain.userdata.analysis.service.VideoAnalysisCallbackHandler;
import repit.repit_api_server.domain.userdata.analysis.service.VideoAnalysisService;
import repit.repit_api_server.global.error.GlobalExceptionHandler;

import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 콜백 본문을 원문 그대로 넘기는지. 객체로 묶어 받으면 모양이 어긋난 본문이 400으로 버려져, 분석 서버는 재전송을
 * 멈추고 이 서버에는 아무 흔적도 남지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class VideoAnalysisControllerTest {

    @Mock
    private VideoAnalysisService videoAnalysisService;
    @Mock
    private VideoAnalysisCallbackHandler callbackHandler;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new VideoAnalysisController(videoAnalysisService, callbackHandler))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void 콜백_본문은_모양과_상관없이_원문_그대로_넘기고_200으로_답한다() throws Exception {
        String odd = "{\"requestId\": [1, 2], \"status\": {}}";

        mockMvc.perform(post("/api/analyses/video/callback").contentType(MediaType.APPLICATION_JSON).content(odd))
                .andExpect(status().isOk());

        verify(callbackHandler).handle(odd);
    }

    @Test
    void 본문이_없어도_200으로_답한다() throws Exception {
        mockMvc.perform(post("/api/analyses/video/callback").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        verify(callbackHandler).handle(isNull());
    }
}
