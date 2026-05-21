package repit.repit_api_server.domain.metadata.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import repit.repit_api_server.domain.metadata.dto.response.UploadResponse;
import repit.repit_api_server.domain.metadata.service.MetaService;

import java.io.IOException;

@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/metaData")
public class MetaDataController {
    private final MetaService metaService;

    @PostMapping("/dataUpload/{userId}")
    public ResponseEntity<UploadResponse> upload(@PathVariable Long userId,
                                                 @RequestParam("file") MultipartFile file,
                                                 @RequestParam("gitUrl") String gitUrl) throws IOException {

        return ResponseEntity.ok(metaService.DataUpload(userId, file, gitUrl));
    }

}
