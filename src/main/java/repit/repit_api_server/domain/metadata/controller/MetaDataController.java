package repit.repit_api_server.domain.metadata.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import repit.repit_api_server.domain.metadata.dto.request.GenerateRequest;
import repit.repit_api_server.domain.metadata.dto.response.GenerateResponse;
import repit.repit_api_server.domain.metadata.dto.response.MetaDataResponse;
import repit.repit_api_server.domain.metadata.service.MetaService;
import repit.repit_api_server.global.client.AiServerClient;

import java.io.IOException;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/metaData")
public class MetaDataController {
    private final MetaService metaService;
    private final AiServerClient aiServerClient;

    @PostMapping(value = "/dataUpload",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<GenerateResponse> upload(@RequestHeader("Authorization") String authorization,
                                                   @RequestPart("file") MultipartFile file,
                                                   @RequestParam List<String> gitUrls) throws IOException {
        MetaDataResponse metaData = metaService.dataUpload(authorization, file, gitUrls);

        GenerateRequest request = GenerateRequest.builder()
                .portfolio_url(metaData.getFileUrl())
                .github_urls(metaData.getGitUrls())
                .callback_url("https://wildcat-startle-rope.ngrok-free.dev/api/v1/ai/callback")
                .build();

        return ResponseEntity.ok(aiServerClient.generate(request));
    }


    @GetMapping("/getMetaData")
    public ResponseEntity<MetaDataResponse> getMetaData(
            @RequestHeader("Authorization") String authorization
    ) {
        return ResponseEntity.ok(metaService.getMetaData(authorization));
    }


    @GetMapping("/isGit")
    public ResponseEntity<Boolean> isGit(
            @RequestHeader("Authorization") String authorization
    ) {
        MetaDataResponse metaData = metaService.getMetaData(authorization);
        return ResponseEntity.ok(!metaData.getGitUrls().isEmpty());
    }

    @GetMapping("/isPortfolio")
    public ResponseEntity<Boolean> isPortfolio(
            @RequestHeader("Authorization") String authorization
    ) {
        MetaDataResponse metaData = metaService.getMetaData(authorization);
        return ResponseEntity.ok(!metaData.getFileUrl().isEmpty());
    }
}
