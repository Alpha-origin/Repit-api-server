package repit.repit_api_server.domain.metadata.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import repit.repit_api_server.domain.metadata.dto.response.MetaDataResponse;
import repit.repit_api_server.domain.metadata.service.MetaService;

import java.io.IOException;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/metaData")
public class MetaDataController {
    private final MetaService metaService;

    @PostMapping(value = "/dataUpload",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MetaDataResponse> upload(@RequestHeader("Authorization") String authorization,
                                                   @RequestPart("file") MultipartFile file,
                                                   @RequestParam List<String> gitUrls) throws IOException {
        return ResponseEntity.ok(metaService.dataUpload(authorization, file, gitUrls));
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
