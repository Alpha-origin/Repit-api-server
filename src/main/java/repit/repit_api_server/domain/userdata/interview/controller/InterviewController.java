package repit.repit_api_server.domain.userdata.interview.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import repit.repit_api_server.domain.userdata.interview.dto.request.CreateInterviewRequest;
import repit.repit_api_server.domain.userdata.interview.dto.request.SaveInterviewRequest;
import repit.repit_api_server.domain.userdata.interview.dto.response.ChatInterviewAllResponse;
import repit.repit_api_server.domain.userdata.interview.dto.response.InterviewPrepareResponse;
import repit.repit_api_server.domain.userdata.interview.dto.response.InterviewResponse;
import repit.repit_api_server.domain.userdata.interview.service.InterviewService;
import repit.repit_api_server.domain.userdata.answer.service.AnswerService;
import repit.repit_api_server.global.common.ApiResponse;

import java.util.List;

@RestController
@RequestMapping("/api/interviews")
@RequiredArgsConstructor
public class InterviewController {
    private final InterviewService interviewService;
    private final AnswerService answerService;

    @PostMapping("/create")
    public ApiResponse<InterviewResponse> createInterview(
            @RequestHeader("Authorization") String authorization,
            @RequestBody CreateInterviewRequest request) {
        return ApiResponse.created(interviewService.createInterview(authorization, request));
    }

    // 면접 시작. 질문 재작성을 접수만 하고, 준비가 끝나면 채팅 서버로 면접 데이터가 넘어간다.
    @PostMapping("/{interviewId}")
    public ApiResponse<InterviewPrepareResponse> prepareInterview(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long interviewId
    ) {
        return ApiResponse.success(interviewService.prepareInterview(authorization, interviewId));
    }

    @GetMapping("/getAll")
    public ApiResponse<List<InterviewResponse>> getAllInterview(
            @RequestHeader("Authorization") String authorization) {
        return ApiResponse.success(interviewService.getAllInterviewsByUserId(authorization));
    }

    @GetMapping("/get")
    public ApiResponse<InterviewResponse> getInterview(
            @RequestParam Long interviewId
    ) {
        return ApiResponse.success(interviewService.getInterviewById(interviewId));
    }

    @GetMapping("/chat")
    public ApiResponse<ChatInterviewAllResponse> getChatInterview(
            @RequestParam Long interviewId
    ) {
        return ApiResponse.success(interviewService.getChatInterview(interviewId));
    }

    @PostMapping("/result")
    public void saveInterview(
            @RequestBody SaveInterviewRequest request
    ) {
        interviewService.saveInterview(request);
    }
}
