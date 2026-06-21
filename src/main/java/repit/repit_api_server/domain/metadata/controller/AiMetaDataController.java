package repit.repit_api_server.domain.metadata.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import repit.repit_api_server.domain.metadata.dto.request.CallbackSuccessRequest;
import repit.repit_api_server.domain.metadata.dto.request.GenerateRequest;
import repit.repit_api_server.domain.metadata.dto.request.MetaDataRequest;
import repit.repit_api_server.domain.metadata.dto.response.CallbackSuccessResponse;
import repit.repit_api_server.domain.metadata.dto.response.GenerateResponse;
import repit.repit_api_server.domain.metadata.dto.response.MetaDataResponse;
import repit.repit_api_server.domain.metadata.service.AiMetaDataService;
import repit.repit_api_server.domain.metadata.service.MetaService;
import repit.repit_api_server.global.client.AiServerClient;
import repit.repit_api_server.global.common.ApiResponse;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/ai")
public class AiMetaDataController {
    private final MetaService metaService;
    private final AiMetaDataService aiMetaDataService;

    private final AiServerClient aiServerClient;

    // AI 서버에 개발 필음
    @PostMapping("/sendMetaData")
    public ResponseEntity<MetaDataResponse> sendMetaData(@RequestHeader("Authorization") String authorization) {
        MetaDataResponse forRequest = metaService.getMetaData(authorization);
        MetaDataRequest request = MetaDataRequest.builder()
                .gitUrl(forRequest.getGitUrl())
                .fileUrl(forRequest.getFileUrl())
                .build();

        MetaDataResponse response = aiServerClient.sendMetaData(authorization, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/generate")
    public ResponseEntity<GenerateResponse> generate(
            @RequestHeader("Authorization") String authorization
    ) {
        MetaDataResponse forRequest = metaService.getMetaData(authorization);
        GenerateRequest request = GenerateRequest.builder()
                .portfolio_url(forRequest.getFileUrl())
                .github_urls(List.of(forRequest.getGitUrl()))
                .callback_url("https://wildcat-startle-rope.ngrok-free.dev/api/v1/ai/callback")
                .build();

        GenerateResponse response = aiServerClient.generate(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/generate-mock")
    public ResponseEntity<GenerateResponse> generateMock(
            @RequestHeader("Authorization") String authorization
    ) {
        MetaDataResponse forRequest = metaService.getMetaData(authorization);
        GenerateRequest request = GenerateRequest.builder()
                .portfolio_url(forRequest.getFileUrl())
                .github_urls(List.of(forRequest.getGitUrl()))
                .callback_url("https://wildcat-startle-rope.ngrok-free.dev/api/v1/ai/callback")
                .build();

        GenerateResponse response = aiServerClient.generateMock(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/callback")
    public ApiResponse<CallbackSuccessResponse> callback(
            @RequestBody CallbackSuccessRequest request
            ) {
        aiMetaDataService.saveResult(request);
        CallbackSuccessResponse response = CallbackSuccessResponse.builder()
                .job_id(request.getJob_id())
                .status(request.getStatus())
                .result(request.getResult())
                .build();
        return ApiResponse.success(response);
    }
}
