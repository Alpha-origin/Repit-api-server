package repit.repit_api_server.domain.userdata.question.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import repit.repit_api_server.domain.userdata.question.dto.request.AnswerRequest;
import repit.repit_api_server.domain.userdata.question.dto.response.AnswerResponse;
import repit.repit_api_server.domain.userdata.question.service.AnswerService;
import repit.repit_api_server.global.common.ApiResponse;

import java.util.List;

@RestController
@RequestMapping("/api/answer")
@RequiredArgsConstructor
public class AnswerController {
    private final AnswerService answerService;

    @PostMapping
    public ApiResponse<AnswerResponse> createAnswer(
            @RequestHeader("Authorization") String authorization,
            @RequestBody AnswerRequest request) {
        return ApiResponse.created(answerService.createAnswer(authorization, request));
    }

    @GetMapping
    public ApiResponse<AnswerResponse> getAnswer(
            @RequestHeader("Authorization") String authorization,
            @RequestParam("answerId") Long answerId) {
        return ApiResponse.success(answerService.getAnswerById(authorization, answerId));
    }

    @GetMapping("/getAll")
    public ApiResponse<List<AnswerResponse>> getAllAnswer(
            @RequestHeader("Authorization") String authorization,
            @RequestParam Long interviewId) {
        return ApiResponse.success(answerService.getAllAnswer(authorization, interviewId));
    }
}
