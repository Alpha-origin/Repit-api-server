package repit.repit_api_server.domain.metadata.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import repit.repit_api_server.domain.metadata.dto.request.AuthMaterialsRequest;
import repit.repit_api_server.domain.metadata.dto.request.MaterialsPayload;
import repit.repit_api_server.domain.metadata.dto.request.MaterialsPayload.SupplementInput;
import repit.repit_api_server.domain.metadata.dto.response.AuthMaterialsResponse.DocumentEntry;
import repit.repit_api_server.domain.metadata.dto.response.AuthMaterialsResponse.SupplementEntry;
import repit.repit_api_server.domain.metadata.dto.response.GenerateResponse;
import repit.repit_api_server.domain.metadata.dto.response.MaterialsResponse;
import repit.repit_api_server.domain.metadata.dto.response.MaterialsSaveResponse;
import repit.repit_api_server.domain.metadata.dto.response.MaterialsSaveResponse.Analysis;
import repit.repit_api_server.domain.metadata.dto.response.MetaDataResponse;
import repit.repit_api_server.domain.metadata.service.MaterialsValidator.ValidDocument;
import repit.repit_api_server.domain.metadata.service.MaterialsValidator.ValidatedMaterials;
import repit.repit_api_server.domain.metadata.support.JobRole;
import repit.repit_api_server.domain.metadata.support.MaterialFileStorage;
import repit.repit_api_server.global.auth.AuthUser;
import repit.repit_api_server.global.client.AuthServerClient;
import repit.repit_api_server.global.exception.ExternalApiException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 마이페이지의 직무·문서·GitHub·보완 입력을 한 번에 저장하고 분석을 다시 요청한다(계약 C-1).
 *
 * <p>저장 전에는 서버의 기존 값을 바꾸지 않는다. 검사를 모두 통과한 뒤에야 새 파일을 올리고,
 * 인증 서버 저장이 실패하면 방금 올린 파일을 지운다. 교체된 옛 파일은 지우지 않는다(옛 업로드 경로와 같다).
 */
@Service
@RequiredArgsConstructor
public class MaterialsService {

    private static final Logger log = LoggerFactory.getLogger(MaterialsService.class);

    private final AuthServerClient authServerClient;
    private final MaterialsValidator validator;
    private final MaterialFileStorage storage;
    private final AnalysisLaunchService analysisLaunchService;

    public MaterialsSaveResponse replace(AuthUser authUser, MaterialsPayload payload, Map<String, MultipartFile> files) {
        MaterialsResponse saved = MaterialsResponse.from(authServerClient.getMaterials(authUser.token()));
        ValidatedMaterials next = validator.validate(payload, files == null ? Map.of() : files, saved);
        try {
            return save(authUser, next, saved);
        } finally {
            next.deleteTempFiles();
        }
    }

    private MaterialsSaveResponse save(AuthUser authUser, ValidatedMaterials next, MaterialsResponse saved) {
        // 같은 내용이면 저장도 분석도 다시 하지 않는다(가정 A-10).
        if (sameAsSaved(next, saved)) {
            return new MaterialsSaveResponse(saved, null);
        }

        List<String> uploaded = new ArrayList<>();
        boolean authCalled = false;
        AuthMaterialsRequest request;
        MaterialsResponse stored;
        try {
            List<DocumentEntry> documents = new ArrayList<>();
            for (ValidDocument document : next.documents()) {
                String fileUrl = document.fileUrl();
                if (document.isNew()) {
                    fileUrl = storage.upload(document.file());
                    uploaded.add(fileUrl);
                }
                documents.add(new DocumentEntry(document.type(), fileUrl, document.fileName(), document.pageCount()));
            }
            request = new AuthMaterialsRequest(next.jobRole().authMajor(), documents, next.gitUrls(),
                    toEntries(next.supplements()));
            authCalled = true;
            stored = MaterialsResponse.from(authServerClient.putMaterials(authUser.token(), request));
        } catch (RuntimeException e) {
            discardUnsavedUploads(authUser, uploaded, authCalled, e);
            throw e;
        }

        return new MaterialsSaveResponse(stored, launchAnalysis(authUser, next.jobRole(), request));
    }

    /**
     * 저장이 실패해 쓸 데가 없어진 새 파일을 지운다.
     *
     * <p>인증 서버를 부르기 전이거나 4xx면 저장되지 않은 게 확실하다. 5xx·타임아웃은 인증 서버가 커밋하고 응답만 늦었을
     * 수 있어, 다시 조회해 저장된 문서가 가리키는 파일은 남긴다. 다시 조회도 실패하면 지우지 않는다(남는 파일이 끊어진
     * 문서보다 낫다).
     */
    private void discardUnsavedUploads(AuthUser authUser, List<String> uploaded, boolean authCalled, RuntimeException failure) {
        if (uploaded.isEmpty()) {
            return;
        }
        if (!authCalled || isClientError(failure)) {
            uploaded.forEach(storage::deleteQuietly);
            return;
        }
        Set<String> referenced;
        try {
            referenced = MaterialsResponse.from(authServerClient.getMaterials(authUser.token())).documents().stream()
                    .map(MaterialsResponse.MaterialDocument::fileUrl)
                    .collect(Collectors.toSet());
        } catch (RuntimeException recheckFailure) {
            log.warn("자료 저장 결과를 확인하지 못해 올린 파일을 지우지 않습니다. userId={}, files={}",
                    authUser.id(), uploaded, recheckFailure);
            return;
        }
        uploaded.stream().filter(url -> !referenced.contains(url)).forEach(storage::deleteQuietly);
    }

    private static boolean isClientError(RuntimeException failure) {
        return failure instanceof ExternalApiException external
                && external.getStatusCode() != null
                && external.getStatusCode().is4xxClientError();
    }

    /**
     * 저장한 자료로 분석을 요청한다. 분석 서버의 새 요청 모양(계약 C-3)이 붙기 전까지는 지금 요청 모양 그대로
     * 첫 문서와 GitHub를 보낸다. 비개발 직무는 그 모양으로는 맞는 분석이 나오지 않아 요청하지 않는다.
     *
     * <p>저장은 이미 끝났으므로 분석 요청이 실패해도 저장을 되돌리지 않는다. 화면이 다시 분석하게 한다.
     */
    private Analysis launchAnalysis(AuthUser authUser, JobRole jobRole, AuthMaterialsRequest request) {
        switch (jobRole.family()) {
            case NON_DEV -> {
                return new Analysis(null, MaterialsSaveResponse.ANALYSIS_SKIPPED);
            }
            case DEV -> {
                // 아래에서 요청한다.
            }
        }
        MetaDataResponse metaData = new MetaDataResponse(request.gitUrls(), request.documents().get(0).fileUrl());
        try {
            GenerateResponse response = analysisLaunchService.launch(authUser.id(), request.major(), metaData);
            return new Analysis(response.getJobId(), MaterialsSaveResponse.ANALYSIS_ACCEPTED);
        } catch (RuntimeException e) {
            log.error("자료는 저장했지만 분석을 요청하지 못했습니다. userId={}", authUser.id(), e);
            return new Analysis(null, MaterialsSaveResponse.ANALYSIS_FAILED);
        }
    }

    private static boolean sameAsSaved(ValidatedMaterials next, MaterialsResponse saved) {
        if (!next.jobRole().name().equals(saved.jobRole())) {
            return false;
        }
        // 새로 올린 파일이 있으면 내용이 같아도 바뀐 것으로 본다.
        if (next.documents().stream().anyMatch(ValidDocument::isNew)) {
            return false;
        }
        List<String> nextDocuments = next.documents().stream().map(d -> d.type() + " " + d.fileUrl()).toList();
        List<String> savedDocuments = saved.documents().stream().map(d -> d.type() + " " + d.fileUrl()).toList();
        List<MaterialsResponse.Supplement> nextSupplements = next.supplements().stream()
                .map(s -> new MaterialsResponse.Supplement(s.title(), s.documentIndex(), s.goal(), s.role(), s.decision(), s.outcome()))
                .toList();
        return nextDocuments.equals(savedDocuments)
                && next.gitUrls().equals(saved.gitUrls())
                && nextSupplements.equals(saved.supplements());
    }

    private static List<SupplementEntry> toEntries(List<SupplementInput> supplements) {
        return supplements.stream()
                .map(s -> new SupplementEntry(s.title(), s.documentIndex(), s.goal(), s.role(), s.decision(), s.outcome()))
                .toList();
    }
}
