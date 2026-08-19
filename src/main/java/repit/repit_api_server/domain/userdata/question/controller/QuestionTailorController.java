package repit.repit_api_server.domain.userdata.question.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import repit.repit_api_server.domain.userdata.question.dto.request.QuestionTailorCallbackRequest;
import repit.repit_api_server.domain.userdata.question.dto.response.QuestionTailorAcceptedResponse;
import repit.repit_api_server.domain.userdata.question.dto.response.QuestionTailorResponse;
import repit.repit_api_server.domain.userdata.question.service.QuestionTailorService;
import repit.repit_api_server.global.common.ApiResponse;

@RestController
@RequestMapping("/api/questions/tailor")
@RequiredArgsConstructor
public class QuestionTailorController {

    private final QuestionTailorService questionTailorService;

    // 면접 시작 직전에 호출한다. 접수만 되고 결과는 콜백으로 온다.
    @PostMapping
    public ApiResponse<QuestionTailorAcceptedResponse> requestTailor(
            @RequestHeader("Authorization") String authorization,
            @RequestParam Long interviewId
    ) {
        return ApiResponse.created(questionTailorService.requestTailor(authorization, interviewId));
    }

    // 분석 서버 전용 콜백. 2xx가 늦거나 실패하면 결과가 폐기되므로 그대로 저장만 하고 응답한다.
    @PostMapping("/callback")
    public ResponseEntity<Void> callback(
            @RequestBody QuestionTailorCallbackRequest request
    ) {
        questionTailorService.handleCallback(request);
        return ResponseEntity.ok().build();
    }

    @GetMapping
    public ApiResponse<QuestionTailorResponse> getTailorResult(
            @RequestHeader("Authorization") String authorization,
            @RequestParam Long interviewId
    ) {
        return ApiResponse.success(questionTailorService.getTailorResult(authorization, interviewId));
    }
}
