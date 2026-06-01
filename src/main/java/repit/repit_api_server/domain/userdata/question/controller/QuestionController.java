package repit.repit_api_server.domain.userdata.question.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
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
}
