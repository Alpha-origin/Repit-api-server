package repit.repit_api_server.domain.userdata.recording.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import repit.repit_api_server.domain.userdata.recording.dto.response.InterviewRecordingResponse;
import repit.repit_api_server.domain.userdata.recording.service.InterviewRecordingService;
import repit.repit_api_server.global.auth.AuthUser;
import repit.repit_api_server.global.error.GlobalExceptionHandler;
import repit.repit_api_server.global.exception.BusinessException;
import repit.repit_api_server.global.response.UserResponse;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 웹이 부르는 모양 그대로 받는지 본다 — multipart의 {@code file} 파트와 필수인 {@code questionId}.
 * 이 이름이 어긋나면 웹은 400만 받고 영상은 사라진다.
 */
@ExtendWith(MockitoExtension.class)
class InterviewRecordingControllerTest {

    private static final Long USER_ID = 7L;

    @Mock
    private InterviewRecordingService recordingService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new InterviewRecordingController(recordingService))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        UserResponse user = new UserResponse();
        ReflectionTestUtils.setField(user, "id", USER_ID);
        AuthUser authUser = new AuthUser(user, "Bearer token");
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(authUser, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 파일과_질문_번호를_받아_201로_답한다() throws Exception {
        when(recordingService.upload(eq(USER_ID), eq(42L), eq(3L), any()))
                .thenReturn(new InterviewRecordingResponse(100L, 42L, 3L, 12L, LocalDateTime.now()));

        mockMvc.perform(multipart("/api/interviews/42/recordings")
                        .file(mp4())
                        .param("questionId", "3"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.recordingId").value(100))
                .andExpect(jsonPath("$.data.questionId").value(3));
    }

    /** 질문 번호가 없으면 어느 답변의 영상인지 알 수 없다. 받아두면 채점에서 버려질 뿐이다. */
    @Test
    void 질문_번호가_없으면_400() throws Exception {
        mockMvc.perform(multipart("/api/interviews/42/recordings").file(mp4()))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(recordingService);
    }

    @Test
    void 파일_파트가_없으면_400() throws Exception {
        mockMvc.perform(multipart("/api/interviews/42/recordings").param("questionId", "3"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 서비스가_거절하면_그_상태와_메시지로_답한다() throws Exception {
        when(recordingService.upload(eq(USER_ID), eq(42L), any(), any()))
                .thenThrow(new BusinessException("MP4 파일만 올릴 수 있습니다.", HttpStatus.UNSUPPORTED_MEDIA_TYPE));

        mockMvc.perform(multipart("/api/interviews/42/recordings").file(mp4()).param("questionId", "3"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.message").value("MP4 파일만 올릴 수 있습니다."));
    }

    private static MockMultipartFile mp4() {
        return new MockMultipartFile("file", "interview-1.mp4", "video/mp4",
                new byte[]{0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'});
    }
}
