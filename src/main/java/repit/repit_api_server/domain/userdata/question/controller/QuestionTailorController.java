package repit.repit_api_server.domain.userdata.question.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import repit.repit_api_server.domain.userdata.question.dto.request.QuestionTailorCallbackRequest;
import repit.repit_api_server.domain.userdata.question.dto.response.QuestionTailorResponse;
import repit.repit_api_server.domain.userdata.question.service.QuestionTailorService;
import repit.repit_api_server.global.common.ApiResponse;

@RestController
@RequestMapping("/api/questions/tailor")
@RequiredArgsConstructor
public class QuestionTailorController {

    private final QuestionTailorService questionTailorService;

    // 분석 서버 전용 콜백. 2xx가 늦거나 실패하면 결과가 폐기되므로 저장과 전달만 하고 바로 응답한다.
    @PostMapping("/callback")
    public ResponseEntity<Void> callback(
            @RequestBody QuestionTailorCallbackRequest request
    ) {
        questionTailorService.handleCallback(request);
        return ResponseEntity.ok().build();
    }

    // 면접 시작(POST /api/interviews) 이후 준비 상태를 확인하는 폴링용 조회.
    @GetMapping
    public ApiResponse<QuestionTailorResponse> getTailorResult(
            @RequestHeader("Authorization") String authorization,
            @RequestParam Long interviewId
    ) {
        return ApiResponse.success(questionTailorService.getTailorResult(authorization, interviewId));
    }
}
