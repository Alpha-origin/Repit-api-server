package repit.repit_api_server.domain.userdata.interview.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import repit.repit_api_server.domain.userdata.interview.dto.request.PersonaRequest;
import repit.repit_api_server.domain.userdata.interview.dto.request.SaveInterviewRequest;
import repit.repit_api_server.domain.userdata.interview.dto.response.InterviewResponse;
import repit.repit_api_server.domain.userdata.interview.entity.PersonaEntity;
import repit.repit_api_server.domain.userdata.interview.service.InterviewService;
import repit.repit_api_server.domain.userdata.question.service.AnswerService;
import repit.repit_api_server.global.common.ApiResponse;

import java.util.List;

@RestController
@RequestMapping("/api/interviews")
@RequiredArgsConstructor
public class InterviewController {
    private final InterviewService interviewService;
    private final AnswerService answerService;

    @PostMapping("/createInterview")
    public ApiResponse<InterviewResponse> createInterview(
            @RequestHeader("Authorization") String authorization,
            @RequestBody PersonaRequest request) throws ClassNotFoundException {
        return ApiResponse.created(interviewService.createInterview(authorization, request));
    }

    @PostMapping("/sendUserData")
    public void sendUserData(
            @RequestHeader("Authorization") String authorization,
            @RequestBody Long interviewId
    ) {
        interviewService.sendUserData(authorization, interviewId);
    }

    @GetMapping("/getAllInterview")
    public ApiResponse<List<InterviewResponse>> getAllInterview(
            @RequestHeader("Authorization") String authorization) {
        return ApiResponse.success(interviewService.getAllInterviewsByUserId(authorization));
    }

    @GetMapping("/getInterview")
    public ApiResponse<InterviewResponse> getInterview(
            @RequestHeader("Authorization") String authorization,
            @RequestParam Long interviewId
    ) {
        return ApiResponse.success(interviewService.getInterviewById(authorization, interviewId));
    }

    @PostMapping("/result")
    public void saveInterview(@RequestHeader("Authorization") String authorization,
                              @RequestBody SaveInterviewRequest request
    ) {
        interviewService.saveInterview(authorization, request);
    }
}
