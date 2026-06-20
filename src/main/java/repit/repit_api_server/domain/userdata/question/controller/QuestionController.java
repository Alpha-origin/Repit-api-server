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
    public ApiResponse<QuestionResponse> createQuestion() {
        return ApiResponse.created(questionService.createQuestion());
    }

    @GetMapping
    public ApiResponse<QuestionResponse> getQuestionById(
            @RequestParam Long questionId) {
        return ApiResponse.success(questionService.getQuestionById(questionId));
    }

    @GetMapping("/getAll")
    public ApiResponse<List<QuestionResponse>> getAllQuestion(
            @RequestParam Long interviewId) {
        return ApiResponse.success(questionService.getAllByInterview(interviewId));
    }
}
