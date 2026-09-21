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
     * 면접 파일 하나를 올린다. 웹은 답할 때마다 그 답변의 음성을, 면접을 멈출 때 면접 화면 전체를 담은
     * 영상을 올린다.
     *
     * <p>둘은 kind로 갈린다 — {@code ANSWER}(기본) 또는 {@code FULL_INTERVIEW}. 질문 번호가 붙었는지로
     * 짐작하지 않는다. 그랬다가는 웹이 번호를 빠뜨린 답변 파일이 조용히 면접 전체 영상이 된다.
     *
     * <p>questionId는 그때 답하던 채팅 서버 질문 번호이고 답변 파일에 필수다. 채점에서 이 번호로
     * 질문·답변에 이어 붙는데, 우리 질문 행은 면접이 끝나야 생기므로 업로드 시점에 가진 유일한
     * 연결 고리다. 빠지면 어느 답변의 것인지 영영 알 수 없어 400으로 돌려보낸다.
     */
    @PostMapping(value = "/{interviewId}/recordings", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<InterviewRecordingResponse> uploadRecording(
            @AuthenticationPrincipal AuthUser authUser,
            @PathVariable Long interviewId,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) Long questionId,
            @RequestPart("file") MultipartFile file) {
        return ApiResponse.created(recordingService.upload(authUser.id(), interviewId, kind, questionId, file));
    }
}
