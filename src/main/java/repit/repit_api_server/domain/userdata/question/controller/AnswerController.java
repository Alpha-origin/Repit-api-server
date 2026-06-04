package repit.repit_api_server.domain.userdata.question.controller;

import org.springframework.web.bind.annotation.*;
import repit.repit_api_server.domain.userdata.question.dto.request.AnswerRequest;
import repit.repit_api_server.domain.userdata.question.dto.response.AnswerResponse;
import repit.repit_api_server.domain.userdata.question.entity.Answer;
import repit.repit_api_server.domain.userdata.question.service.AnswerService;

@RestController
@RequestMapping("/api/v1/interview")
public class AnswerController {
    private AnswerService answerService;

    @PostMapping("/createAnswer")
    public AnswerResponse createAnswer(@RequestHeader("Authorization") String authorization,
                                       @RequestBody AnswerRequest request) {
        return answerService.createAnswer(authorization, request);
    }
}
