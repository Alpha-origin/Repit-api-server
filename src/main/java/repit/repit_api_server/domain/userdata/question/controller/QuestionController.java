package repit.repit_api_server.domain.userdata.question.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import repit.repit_api_server.domain.userdata.question.dto.response.QuestionResponse;
import repit.repit_api_server.domain.userdata.question.service.QuestionService;
import repit.repit_api_server.global.common.ApiResponse;

import java.util.List;

@RestController
@RequestMapping("/api/question")
@RequiredArgsConstructor
public class QuestionController {
    private final QuestionService questionService;

    @PostMapping
    public ApiResponse<QuestionResponse> createQuestion(
            @RequestHeader("Authorization") String authorization) {
        return ApiResponse.created(questionService.createQuestion(authorization));
    }

    @GetMapping
    public ApiResponse<QuestionResponse> getQuestionById(
            @RequestHeader("Authorization") String authorization,
            @RequestParam Long questionId) {
        return ApiResponse.success(questionService.getQuestionById(authorization, questionId));
    }

    @GetMapping("/getAll")
    public ApiResponse<List<QuestionResponse>> getAllQuestion(
            @RequestHeader("Authorization") String authorization,
            @RequestParam Long interviewId) {
        return ApiResponse.success(questionService.getAllByInterview(authorization, interviewId));
    }
}
