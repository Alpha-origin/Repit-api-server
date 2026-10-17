package repit.repit_api_server.domain.metadata.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import repit.repit_api_server.domain.metadata.dto.request.MaterialsPayload;
import repit.repit_api_server.domain.metadata.dto.response.GenerateResponse;
import repit.repit_api_server.domain.metadata.dto.response.MaterialsResponse;
import repit.repit_api_server.domain.metadata.dto.response.MaterialsSaveResponse;
import repit.repit_api_server.domain.metadata.dto.response.MetaDataResponse;
import repit.repit_api_server.domain.metadata.service.AnalysisLaunchService;
import repit.repit_api_server.domain.metadata.service.MaterialsService;
import repit.repit_api_server.domain.metadata.service.MetaService;
import repit.repit_api_server.global.auth.AuthUser;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/metaData")
public class MetaDataController {
    private final MetaService metaService;
    private final AnalysisLaunchService analysisLaunchService;
    private final MaterialsService materialsService;

    @PostMapping(value = "/dataUpload",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<GenerateResponse> upload(@AuthenticationPrincipal AuthUser authUser,
                                                   @RequestPart("file") MultipartFile file,
                                                   @RequestParam List<String> gitUrls) throws IOException {
        MetaDataResponse metaData = metaService.dataUpload(authUser.token(), file, gitUrls);

        // 올린 자료로 곧바로 분석을 시작한다. 접수까지 함께 해야 이 분석에 주인이 남고,
        // 주인이 없으면 나중에 면접을 열 때 이 결과를 찾지 못한다.
        return ResponseEntity.ok(analysisLaunchService.launch(authUser.id(), authUser.user().getMajor(), metaData));
    }


    @GetMapping("/getMetaData")
    public ResponseEntity<MetaDataResponse> getMetaData(
            @AuthenticationPrincipal AuthUser authUser
    ) {
        return ResponseEntity.ok(metaService.getMetaData(authUser.token()));
    }

    // 마이페이지 편집 화면이 처음 채울 저장값.
    @GetMapping("/materials")
    public ResponseEntity<MaterialsResponse> getMaterials(
            @AuthenticationPrincipal AuthUser authUser
    ) {
        return ResponseEntity.ok(metaService.getMaterials(authUser.token()));
    }

    /**
     * 직무·문서·GitHub·보완 입력을 한 번에 저장한다. payload 파트(JSON)와 새로 첨부한 PDF(file0~file4)가 온다.
     * 규칙에 걸리면 422와 사유 목록(MaterialErrorResponse)이 나간다.
     */
    @PutMapping(value = "/materials", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MaterialsSaveResponse> putMaterials(
            @AuthenticationPrincipal AuthUser authUser,
            @RequestPart("payload") MaterialsPayload payload,
            @RequestParam Map<String, MultipartFile> files
    ) {
        return ResponseEntity.ok(materialsService.replace(authUser, payload, files));
    }


    // 자료를 한 번도 올리지 않은 사용자는 인증 서버가 두 값을 null로 준다. 없는 것이지 오류가 아니다.
    @GetMapping("/isGit")
    public ResponseEntity<Boolean> isGit(
            @AuthenticationPrincipal AuthUser authUser
    ) {
        MetaDataResponse metaData = metaService.getMetaData(authUser.token());
        return ResponseEntity.ok(metaData != null && metaData.getGitUrls() != null
                && !metaData.getGitUrls().isEmpty());
    }

    @GetMapping("/isPortfolio")
    public ResponseEntity<Boolean> isPortfolio(
            @AuthenticationPrincipal AuthUser authUser
    ) {
        MetaDataResponse metaData = metaService.getMetaData(authUser.token());
        return ResponseEntity.ok(metaData != null && metaData.getFileUrl() != null
                && !metaData.getFileUrl().isBlank());
    }
}
