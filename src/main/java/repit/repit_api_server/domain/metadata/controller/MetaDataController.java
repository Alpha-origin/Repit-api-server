package repit.repit_api_server.domain.metadata.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import repit.repit_api_server.domain.metadata.service.MetaService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/metaData")
public class MetaDataController {
    private final MetaService metaService;

    @PostMapping("/upload")
    public void upload(@RequestPart("file") MultipartFile file,
                       @RequestPart("url")  String url) {

    }

}
