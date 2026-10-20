package repit.repit_api_server.domain.userdata.analysis.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import repit.repit_api_server.domain.userdata.analysis.service.VideoAnalysisCallbackHandler;
import repit.repit_api_server.domain.userdata.analysis.service.VideoAnalysisService;
import repit.repit_api_server.global.error.GlobalExceptionHandler;
import repit.repit_api_server.global.exception.BusinessException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

    /** 1MiB를 넘는 본문은 읽기 전에 413으로 끊는다. 넘는 원문을 따로 남기는 기록에도 쌓지 않는다. */
    @Test
    void 밝힌_길이가_상한을_넘으면_읽지_않고_413이다() throws Exception {
        byte[] big = new byte[VideoAnalysisCallbackHandler.MAX_BODY_BYTES + 1];
        java.util.Arrays.fill(big, (byte) 'a');

        mockMvc.perform(post("/api/analyses/video/callback").contentType(MediaType.APPLICATION_JSON).content(big))
                .andExpect(status().isContentTooLarge());

        verifyNoInteractions(callbackHandler);
    }

    @Test
    void 상한까지는_받는다() throws Exception {
        String atLimit = "{\"pad\":\"" + "a".repeat(VideoAnalysisCallbackHandler.MAX_BODY_BYTES - 10) + "\"}";
        assertThat(atLimit.getBytes(StandardCharsets.UTF_8)).hasSize(VideoAnalysisCallbackHandler.MAX_BODY_BYTES);

        mockMvc.perform(post("/api/analyses/video/callback").contentType(MediaType.APPLICATION_JSON).content(atLimit))
                .andExpect(status().isOk());

        verify(callbackHandler).handle(atLimit);
    }

    /** 길이를 밝히지 않은(chunked) 본문도 상한까지만 읽는다. */
    @Test
    void 길이를_밝히지_않은_본문도_상한을_넘으면_413이다() {
        MockHttpServletRequest chunked = new MockHttpServletRequest("POST", "/api/analyses/video/callback") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        chunked.setContent(new byte[VideoAnalysisCallbackHandler.MAX_BODY_BYTES + 1]);
        VideoAnalysisController controller = new VideoAnalysisController(videoAnalysisService, callbackHandler);

        assertThatThrownBy(() -> controller.callback(chunked))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE));
        verifyNoInteractions(callbackHandler);
    }

    /** UTF-8로 읽을 수 없는 본문은 JSON으로 해석하지 않고 깨진 바이트를 바꾼 원문만 따로 남긴다. */
    @Test
    void UTF8이_아닌_본문은_따로_남기고_200이다() throws Exception {
        byte[] latin1 = "{\"status\":\"r\u00e9ady\"}".getBytes(StandardCharsets.ISO_8859_1);

        mockMvc.perform(post("/api/analyses/video/callback").contentType(MediaType.APPLICATION_JSON).content(latin1))
                .andExpect(status().isOk());

        verify(callbackHandler).keepUndecodable(anyString());
        verify(callbackHandler, never()).handle(any());
    }
}
