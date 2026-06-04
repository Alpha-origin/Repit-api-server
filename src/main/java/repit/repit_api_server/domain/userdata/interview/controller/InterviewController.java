package repit.repit_api_server.domain.userdata.interview.controller;

import org.springframework.web.bind.annotation.*;
import repit.repit_api_server.domain.userdata.interview.dto.response.InterviewResponse;
import repit.repit_api_server.domain.userdata.interview.entity.Persona;
import repit.repit_api_server.domain.userdata.interview.service.InterviewService;
import repit.repit_api_server.global.common.ApiResponse;

@RestController
@RequestMapping("/api/v1/interview")
public class InterviewController {
    private InterviewService interviewService;

    @PostMapping("/createInterview")
    public ApiResponse<InterviewResponse> createInterview(
            @RequestHeader("Authorization") String authorization,
            @RequestBody Persona persona) {
        return ApiResponse.created(interviewService.createInterview(authorization, persona));
    }

    @PostMapping("/sendUserData")
    public void sendUserData(
            @RequestHeader("Authorization") String authorization,
            @RequestBody Long interviewId
    ) {
        interviewService.sendUserData(authorization, interviewId);
    }
}
