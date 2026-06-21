package repit.repit_api_server.domain.metadata.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import repit.repit_api_server.domain.metadata.dto.request.SendDataRequest;
import repit.repit_api_server.domain.metadata.dto.response.MetaDataResponse;
import repit.repit_api_server.domain.metadata.service.MetaService;

import java.io.IOException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/metaData")
public class MetaDataController {
    private final MetaService metaService;

    @PostMapping("/dataUpload")
    public ResponseEntity<MetaDataResponse> upload(@RequestHeader String authorization,
                                                   @RequestPart("file") MultipartFile file,
                                                   @RequestBody SendDataRequest request) throws IOException {
        return ResponseEntity.ok(metaService.dataUpload(authorization, file, request));
    }


    @GetMapping("/getMetaData")
    public ResponseEntity<MetaDataResponse> getMetaData(
            @RequestHeader("Authorization") String authorization
    ) {
        return ResponseEntity.ok(metaService.getMetaData(authorization));
    }
}
