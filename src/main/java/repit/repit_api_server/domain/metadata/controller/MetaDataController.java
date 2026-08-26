package repit.repit_api_server.domain.metadata.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import repit.repit_api_server.domain.metadata.dto.response.GenerateResponse;
import repit.repit_api_server.domain.metadata.dto.response.MetaDataResponse;
import repit.repit_api_server.domain.metadata.service.AnalysisLaunchService;
import repit.repit_api_server.domain.metadata.service.MetaService;

import java.io.IOException;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/metaData")
public class MetaDataController {
    private final MetaService metaService;
    private final AnalysisLaunchService analysisLaunchService;

    @PostMapping(value = "/dataUpload",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<GenerateResponse> upload(@RequestHeader("Authorization") String authorization,
                                                   @RequestPart("file") MultipartFile file,
                                                   @RequestParam List<String> gitUrls) throws IOException {
        MetaDataResponse metaData = metaService.dataUpload(authorization, file, gitUrls);

        // 올린 자료로 곧바로 분석을 시작한다. 접수까지 함께 해야 이 분석에 주인이 남고,
        // 주인이 없으면 나중에 면접을 열 때 이 결과를 찾지 못한다.
        return ResponseEntity.ok(analysisLaunchService.launch(authorization, metaData));
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
