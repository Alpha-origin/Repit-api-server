package repit.repit_api_server.domain.userdata.recording.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import repit.repit_api_server.domain.userdata.recording.dto.response.InterviewRecordingResponse;
import repit.repit_api_server.domain.userdata.recording.service.InterviewRecordingService;
import repit.repit_api_server.global.auth.AuthUser;
import repit.repit_api_server.global.common.ApiResponse;

@RestController
@RequestMapping("/api/interviews")
@RequiredArgsConstructor
public class InterviewRecordingController {
    private final InterviewRecordingService recordingService;

    /**
     * 웹은 음성 답변 하나를 마칠 때마다 녹화 파일 하나를 올린다.
     *
     * <p>questionId는 그때 답하던 채팅 서버 질문 번호이고 필수다. 영상은 채점에서 이 번호로 질문·답변에
     * 이어 붙는데, 우리 질문 행은 면접이 끝나야 생기므로 업로드 시점에 가진 유일한 연결 고리다.
     * 빠지면 어느 답변의 영상인지 영영 알 수 없다.
     */
    @PostMapping(value = "/{interviewId}/recordings", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<InterviewRecordingResponse> uploadRecording(
            @AuthenticationPrincipal AuthUser authUser,
            @PathVariable Long interviewId,
            @RequestParam Long questionId,
            @RequestPart("file") MultipartFile file) {
        return ApiResponse.created(recordingService.upload(authUser.id(), interviewId, questionId, file));
    }
}
