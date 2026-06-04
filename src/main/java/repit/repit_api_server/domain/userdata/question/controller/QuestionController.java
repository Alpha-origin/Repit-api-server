package repit.repit_api_server.domain.userdata.question.controller;

import org.springframework.web.bind.annotation.*;
import repit.repit_api_server.domain.userdata.question.dto.response.QuestionResponse;
import repit.repit_api_server.domain.userdata.question.service.QuestionService;
import repit.repit_api_server.global.common.ApiResponse;

@RestController
@RequestMapping("/api/v1/interview")
public class QuestionController {
    private QuestionService questionService;

    @PostMapping("/createQuestion")
    public ApiResponse<QuestionResponse> createQuestion(@RequestHeader("Authorization") String authorization) {
        return ApiResponse.created(questionService.createQuestion(authorization));
    }

    @GetMapping("/getQuestionById")
    public ApiResponse<QuestionResponse> getQuestionById(
            @RequestHeader("Authorization") String authorization,
            @RequestParam Long questionId) {
        return ApiResponse.success(questionService.getQuestionById(authorization, questionId));
    }
}
