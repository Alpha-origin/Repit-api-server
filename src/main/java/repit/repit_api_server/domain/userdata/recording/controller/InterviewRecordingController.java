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

    // 웹은 음성 답변 하나를 마칠 때마다 녹화 파일 하나를 올린다. questionId는 그때 답하던 채팅 질문 번호다.
    @PostMapping(value = "/{interviewId}/recordings", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<InterviewRecordingResponse> uploadRecording(
            @AuthenticationPrincipal AuthUser authUser,
            @PathVariable Long interviewId,
            @RequestParam(required = false) Long questionId,
            @RequestPart("file") MultipartFile file) {
        return ApiResponse.created(recordingService.upload(authUser.id(), interviewId, questionId, file));
    }
}
