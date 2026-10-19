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
        AuthMaterialsRequest request = null;
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
            stored = recoverOrDiscard(authUser, next.jobRole(), request, uploaded, authCalled, e);
        }

        return new MaterialsSaveResponse(stored, launchAnalysis(authUser, next.jobRole(), stored));
    }

    /**
     * 인증 서버 저장이 실패한 것처럼 보일 때, 실제로 저장됐는지 보고 이어 갈지 정리할지 정한다.
     *
     * <p>인증 서버를 부르기 전이면 저장되지 않은 게 확실해 새 파일을 지우고 실패를 던진다. 부른 뒤에는 4xx여도 확실하지 않다.
     * 저장 호출은 재시도하므로, 앞 시도가 커밋하고 응답만 늦은 뒤(타임아웃) 다음 시도가 4xx를 받을 수 있다. 그래서 실패 종류와
     * 상관없이 다시 조회한다. 보낸 내용이 그대로 저장돼 있으면 저장은 끝난 것이라 그 값으로 이어 간다(사용자가 실패로 보고 다시
     * 올리지 않게). 다르면 저장된 문서가 가리키지 않는 새 파일만 지우고 실패를 던진다. 다시 조회도 실패하면 아무것도 지우지
     * 않는다(남는 파일이 끊어진 문서보다 낫다).
     */
    private MaterialsResponse recoverOrDiscard(AuthUser authUser, JobRole jobRole, AuthMaterialsRequest request,
                                               List<String> uploaded, boolean authCalled, RuntimeException failure) {
        if (!authCalled) {
            uploaded.forEach(storage::deleteQuietly);
            throw failure;
        }
        MaterialsResponse rechecked;
        try {
            rechecked = MaterialsResponse.from(authServerClient.getMaterials(authUser.token()));
        } catch (RuntimeException recheckFailure) {
            log.warn("자료 저장 결과를 확인하지 못해 올린 파일을 지우지 않습니다. userId={}, files={}",
                    authUser.id(), uploaded, recheckFailure);
            throw failure;
        }
        if (isStored(rechecked, jobRole, request)) {
            log.info("인증 서버 응답은 실패였지만 저장은 끝나 있어 성공으로 이어 갑니다. userId={}, 원인={}",
                    authUser.id(), failure.getMessage());
            return rechecked;
        }
        Set<String> referenced = rechecked.documents().stream()
                .map(MaterialsResponse.MaterialDocument::fileUrl)
                .collect(Collectors.toSet());
        uploaded.stream().filter(url -> !referenced.contains(url)).forEach(storage::deleteQuietly);
        throw failure;
    }

    private static boolean isStored(MaterialsResponse stored, JobRole jobRole, AuthMaterialsRequest request) {
        List<String> storedDocuments = stored.documents().stream().map(d -> d.type() + " " + d.fileUrl()).toList();
        List<String> sentDocuments = request.documents().stream().map(d -> d.type() + " " + d.fileUrl()).toList();
        List<MaterialsResponse.Supplement> sentSupplements = request.supplements().stream()
                .map(s -> new MaterialsResponse.Supplement(s.title(), s.documentIndex(), s.goal(), s.role(), s.decision(), s.outcome()))
                .toList();
        return jobRole.name().equals(stored.jobRole())
                && storedDocuments.equals(sentDocuments)
                && stored.gitUrls().equals(request.gitUrls())
                && stored.supplements().equals(sentSupplements);
    }

    /**
     * 저장된 자료로 분석을 요청한다. 분석 서버의 새 요청 모양(계약 C-3)이 붙기 전까지는 지금 요청 모양 그대로
     * 포트폴리오 문서와 GitHub를 보낸다({@link MaterialsResponse#toAnalysisInput()}, 다시 분석하는 길과 같다).
     * 비개발 직무는 그 모양으로는 맞는 분석이 나오지 않아 요청하지 않는다.
     *
     * <p>저장은 이미 끝났으므로 분석 요청이 실패해도 저장을 되돌리지 않는다. 화면이 다시 분석하게 한다.
     */
    private Analysis launchAnalysis(AuthUser authUser, JobRole jobRole, MaterialsResponse stored) {
        if (!jobRole.supportsCurrentProfileRequest()) {
            return new Analysis(null, MaterialsSaveResponse.ANALYSIS_SKIPPED);
        }
        MetaDataResponse input = stored.toAnalysisInput();
        // 개발 직무는 포트폴리오가 필수라 검사를 통과했으면 있어야 한다. 없으면 인증 서버가 돌려준 저장값이 이상한 것이다.
        if (input.getFileUrl() == null) {
            log.error("자료는 저장했지만 저장값에 포트폴리오 문서가 없어 분석을 요청하지 못했습니다. userId={}", authUser.id());
            return new Analysis(null, MaterialsSaveResponse.ANALYSIS_FAILED);
        }
        try {
            GenerateResponse response = analysisLaunchService.launch(authUser.id(), jobRole.authMajor(), input);
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
