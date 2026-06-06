package repit.repit_api_server.domain.userdata.question.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import repit.repit_api_server.domain.userdata.interview.entity.Interview;
import repit.repit_api_server.domain.userdata.question.dto.response.QuestionResponse;
import repit.repit_api_server.domain.userdata.question.service.QuestionService;
import repit.repit_api_server.global.common.ApiResponse;

import java.util.List;

@RestController
@RequestMapping("/api/v1/interview")
@RequiredArgsConstructor
public class QuestionController {
    private final QuestionService questionService;

    @PostMapping("/createQuestion")
    public ApiResponse<QuestionResponse> createQuestion(
            @RequestHeader("Authorization") String authorization) {
        return ApiResponse.created(questionService.createQuestion(authorization));
    }

    @GetMapping("/getQuestionById")
    public ApiResponse<QuestionResponse> getQuestionById(
            @RequestHeader("Authorization") String authorization,
            @RequestParam Long questionId) {
        return ApiResponse.success(questionService.getQuestionById(authorization, questionId));
    }

    @GetMapping("/getAllQuestion")
    public ApiResponse<List<QuestionResponse>> getAllQuestion(
            @RequestHeader("Authorization") String authorization,
            @RequestBody Interview interview) {
        return ApiResponse.success(questionService.getAllByInterview(authorization, interview));
    }
}
