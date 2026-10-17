package repit.repit_api_server.domain.metadata.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import repit.repit_api_server.domain.metadata.dto.request.MaterialsPayload;
import repit.repit_api_server.domain.metadata.dto.request.MaterialsPayload.DocumentInput;
import repit.repit_api_server.domain.metadata.dto.request.MaterialsPayload.SupplementInput;
import repit.repit_api_server.domain.metadata.dto.response.MaterialErrorResponse.MaterialError;
import repit.repit_api_server.domain.metadata.dto.response.MaterialErrorResponse.Reason;
import repit.repit_api_server.domain.metadata.dto.response.MaterialsResponse;
import repit.repit_api_server.domain.metadata.dto.response.MaterialsResponse.MaterialDocument;
import repit.repit_api_server.domain.metadata.exception.MaterialValidationException;
import repit.repit_api_server.domain.metadata.support.JobRole;
import repit.repit_api_server.domain.metadata.support.MaterialFileStorage;
import repit.repit_api_server.domain.metadata.support.PdfInspector;
import repit.repit_api_server.global.exception.BusinessException;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 직무·자료 저장 요청을 직무 명세대로 검사한다. 걸린 규칙은 모아서 한 번에 422로 돌려준다.
 *
 * <p>순서: 직무 → 문서(직무 변경, 종류, 파일, 중복, 쪽수 합계, 필수 종류) → GitHub → 보완 입력.
 * 직무를 알 수 없으면 나머지 규칙을 정할 수 없어 바로 멈춘다.
 */
@Component
@RequiredArgsConstructor
public class MaterialsValidator {

    static final int MAX_DOCUMENTS = 5;
    static final int MAX_TOTAL_PAGES = 50;
    static final long MAX_FILE_BYTES = 50L * 1024 * 1024;
    static final int MAX_SUPPLEMENTS = 3;
    static final int MAX_SUPPLEMENT_TITLE = 80;
    static final int MAX_SUPPLEMENT_ITEM = 500;
    private static final int MAX_FILE_NAME = 255;

    // 저장소 주소 또는 계정 주소. 다른 사이트 주소나 아이디만 적은 값은 받지 않는다.
    private static final Pattern GITHUB_URL = Pattern.compile(
            "^(https?://)?(www\\.)?github\\.com/[A-Za-z0-9-]+(/[A-Za-z0-9._-]+)?/?$", Pattern.CASE_INSENSITIVE);
    // 새 파일 파트 이름. multipart에는 payload 파트도 섞여 오므로 이 이름만 파일로 읽는다.
    private static final Pattern FILE_KEY = Pattern.compile("file[0-4]");

    private final PdfInspector pdfInspector;
    private final MaterialFileStorage storage;

    /** 검사를 통과한 저장 내용. 문서는 요청 순서 그대로다. */
    public record ValidatedMaterials(JobRole jobRole, List<ValidDocument> documents,
                                     List<String> gitUrls, List<SupplementInput> supplements) {
    }

    /** 저장된 문서면 fileUrl, 새로 올린 문서면 bytes가 있다. */
    public record ValidDocument(String type, String fileUrl, byte[] bytes, String fileName, int pageCount) {

        public boolean isNew() {
            return bytes != null;
        }
    }

    public ValidatedMaterials validate(MaterialsPayload payload, Map<String, MultipartFile> files, MaterialsResponse saved) {
        JobRole job = JobRole.find(payload.jobRole()).orElseThrow(() ->
                new MaterialValidationException(List.of(error("jobRole", Reason.JOB_ROLE_REQUIRED, null))));

        List<MaterialError> errors = new ArrayList<>();
        List<DocumentInput> inputs = payload.documents() == null ? List.of() : payload.documents();
        List<ValidDocument> documents = validateDocuments(job, inputs, files, saved, errors);
        List<String> gitUrls = validateGithub(job, payload.gitUrls(), errors);
        List<SupplementInput> supplements = validateSupplements(job, payload.supplements(), inputs.size(), errors);

        if (!errors.isEmpty()) {
            throw new MaterialValidationException(errors);
        }
        return new ValidatedMaterials(job, documents, gitUrls, supplements);
    }

    private List<ValidDocument> validateDocuments(JobRole job, List<DocumentInput> inputs, Map<String, MultipartFile> files,
                                                  MaterialsResponse saved, List<MaterialError> errors) {
        if (inputs.isEmpty()) {
            errors.add(error("documents", Reason.NO_DOCUMENT, null));
            return List.of();
        }
        if (inputs.size() > MAX_DOCUMENTS) {
            errors.add(error("documents", Reason.TOO_MANY_DOCUMENTS, null));
            return List.of();
        }

        int errorsBefore = errors.size();
        boolean keepsSaved = keepsSavedMaterials(saved, job);
        Set<String> newFileHashes = new HashSet<>();
        Set<String> existingUrls = new HashSet<>();
        List<ValidDocument> documents = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            String field = "documents[" + i + "]";
            DocumentInput input = inputs.get(i);
            if (input == null) {
                throw new BusinessException(field + "가 비어 있습니다.", HttpStatus.BAD_REQUEST);
            }
            if (!job.allowsType(input.type())) {
                errors.add(error(field, Reason.DOC_TYPE_NOT_ALLOWED, input.type()));
                continue;
            }
            ValidDocument document;
            if (MaterialsPayload.SOURCE_EXISTING.equals(input.source())) {
                document = existingDocument(input, field, keepsSaved, saved, existingUrls, errors);
            } else if (MaterialsPayload.SOURCE_NEW.equals(input.source())) {
                document = newDocument(input, field, files, newFileHashes, errors);
            } else {
                throw new BusinessException(field + ".source는 NEW 또는 EXISTING이어야 합니다.", HttpStatus.BAD_REQUEST);
            }
            if (document != null) {
                documents.add(document);
            }
        }

        // 쪽수 합계는 모든 문서를 읽었을 때만 의미가 있다.
        if (errors.size() == errorsBefore) {
            int totalPages = documents.stream().mapToInt(ValidDocument::pageCount).sum();
            if (totalPages > MAX_TOTAL_PAGES) {
                errors.add(error("documents", Reason.TOO_MANY_PAGES, String.valueOf(totalPages)));
            }
        }
        if (inputs.stream().noneMatch(input -> job.isRequiredType(input.type()))) {
            errors.add(error("documents", Reason.REQUIRED_DOC_MISSING, String.join(",", job.requiredTypes())));
        }
        return documents;
    }

    /**
     * 저장된 문서를 그대로 쓸 수 있는지. 개발 3직무 사이만 유지한다.
     * 직무를 고른 적 없는 옛 사용자는 백엔드로 본다(가정 A-7).
     */
    private static boolean keepsSavedMaterials(MaterialsResponse saved, JobRole next) {
        Optional<JobRole> savedJob = saved.jobRole() == null ? Optional.of(JobRole.BACKEND) : JobRole.find(saved.jobRole());
        return savedJob.map(job -> job.keepsMaterialsTo(next)).orElse(false);
    }

    private ValidDocument existingDocument(DocumentInput input, String field, boolean keepsSaved, MaterialsResponse saved,
                                           Set<String> existingUrls, List<MaterialError> errors) {
        if (!keepsSaved) {
            errors.add(error(field, Reason.MATERIALS_RESET_REQUIRED, null));
            return null;
        }
        MaterialDocument savedDocument = saved.documents().stream()
                .filter(d -> d.fileUrl() != null && d.fileUrl().equals(input.fileUrl()))
                .findFirst()
                .orElse(null);
        if (savedDocument == null) {
            errors.add(error(field, Reason.EXISTING_NOT_FOUND, null));
            return null;
        }
        if (!existingUrls.add(input.fileUrl())) {
            errors.add(error(field, Reason.DUPLICATE_FILE, null));
            return null;
        }

        Integer pages = savedDocument.pageCount();
        if (pages == null) {
            // 옛 업로드 경로로 올린 PDF는 쪽수를 센 적이 없어 지금 센다.
            Optional<byte[]> bytes = storage.download(input.fileUrl());
            if (bytes.isEmpty()) {
                errors.add(error(field, Reason.EXISTING_NOT_FOUND, null));
                return null;
            }
            PdfInspector.Result result = pdfInspector.inspect(bytes.get());
            if (result.status() != PdfInspector.Status.OK) {
                errors.add(error(field, unreadableReason(result.status()), null));
                return null;
            }
            pages = result.pageCount();
        }
        return new ValidDocument(input.type(), input.fileUrl(), null, savedDocument.fileName(), pages);
    }

    private ValidDocument newDocument(DocumentInput input, String field, Map<String, MultipartFile> files,
                                      Set<String> newFileHashes, List<MaterialError> errors) {
        MultipartFile file = input.fileKey() == null || !FILE_KEY.matcher(input.fileKey()).matches()
                ? null : files.get(input.fileKey());
        if (file == null || file.isEmpty()) {
            throw new BusinessException(field + "의 파일(" + input.fileKey() + ")이 요청에 없습니다.", HttpStatus.BAD_REQUEST);
        }
        if (!looksLikePdf(file)) {
            errors.add(error(field, Reason.NOT_PDF, null));
            return null;
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            errors.add(error(field, Reason.FILE_TOO_LARGE, null));
            return null;
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            errors.add(error(field, Reason.BROKEN_PDF, null));
            return null;
        }
        if (!newFileHashes.add(sha256(bytes))) {
            errors.add(error(field, Reason.DUPLICATE_FILE, null));
            return null;
        }
        PdfInspector.Result result = pdfInspector.inspect(bytes);
        if (result.status() != PdfInspector.Status.OK) {
            errors.add(error(field, unreadableReason(result.status()), null));
            return null;
        }
        return new ValidDocument(input.type(), null, bytes, fileNameOf(file), result.pageCount());
    }

    private List<String> validateGithub(JobRole job, List<String> requested, List<MaterialError> errors) {
        List<String> gitUrls = requested == null ? List.of()
                : requested.stream().map(url -> url == null ? "" : url.strip()).toList();
        switch (job.githubRule()) {
            case REQUIRED -> {
                if (gitUrls.isEmpty()) {
                    errors.add(error("gitUrls", Reason.GITHUB_REQUIRED, null));
                }
                checkGithubFormat(gitUrls, errors);
            }
            case OPTIONAL -> checkGithubFormat(gitUrls, errors);
            case HIDDEN -> {
                if (!gitUrls.isEmpty()) {
                    errors.add(error("gitUrls", Reason.GITHUB_NOT_ALLOWED, null));
                }
            }
        }
        return gitUrls;
    }

    private static void checkGithubFormat(List<String> gitUrls, List<MaterialError> errors) {
        for (int i = 0; i < gitUrls.size(); i++) {
            if (!GITHUB_URL.matcher(gitUrls.get(i)).matches()) {
                errors.add(error("gitUrls[" + i + "]", Reason.INVALID_GITHUB_URL, null));
            }
        }
    }

    /** 내용이 하나도 없는 프로젝트(화면에 펼쳐만 둔 빈 칸)는 저장하지 않는다. */
    private List<SupplementInput> validateSupplements(JobRole job, List<SupplementInput> requested, int documentCount,
                                                      List<MaterialError> errors) {
        List<SupplementInput> supplements = new ArrayList<>();
        List<Integer> positions = new ArrayList<>();
        if (requested != null) {
            for (int i = 0; i < requested.size(); i++) {
                if (hasContent(requested.get(i))) {
                    supplements.add(requested.get(i));
                    positions.add(i);
                }
            }
        }
        if (supplements.isEmpty()) {
            return List.of();
        }
        if (!job.supplementsAllowed()) {
            errors.add(error("supplements", Reason.SUPPLEMENT_NOT_ALLOWED, null));
            return List.of();
        }
        if (supplements.size() > MAX_SUPPLEMENTS) {
            errors.add(error("supplements", Reason.SUPPLEMENT_INVALID, null));
            return List.of();
        }
        for (int i = 0; i < supplements.size(); i++) {
            SupplementInput s = supplements.get(i);
            boolean tooLong = longerThan(s.title(), MAX_SUPPLEMENT_TITLE)
                    || longerThan(s.goal(), MAX_SUPPLEMENT_ITEM) || longerThan(s.role(), MAX_SUPPLEMENT_ITEM)
                    || longerThan(s.decision(), MAX_SUPPLEMENT_ITEM) || longerThan(s.outcome(), MAX_SUPPLEMENT_ITEM);
            boolean badIndex = s.documentIndex() != null && (s.documentIndex() < 0 || s.documentIndex() >= documentCount);
            if (tooLong || badIndex) {
                errors.add(error("supplements[" + positions.get(i) + "]", Reason.SUPPLEMENT_INVALID, null));
            }
        }
        return List.copyOf(supplements);
    }

    private static boolean hasContent(SupplementInput s) {
        return s != null && (notBlank(s.title()) || s.documentIndex() != null || notBlank(s.goal())
                || notBlank(s.role()) || notBlank(s.decision()) || notBlank(s.outcome()));
    }

    private static boolean looksLikePdf(MultipartFile file) {
        String name = file.getOriginalFilename();
        return "application/pdf".equalsIgnoreCase(file.getContentType())
                || (name != null && name.toLowerCase(Locale.ROOT).endsWith(".pdf"));
    }

    /** 일부 브라우저가 보내는 경로를 떼고, 화면에 보일 이름만 남긴다. */
    private static String fileNameOf(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            return null;
        }
        String base = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1).strip();
        return base.length() > MAX_FILE_NAME ? base.substring(0, MAX_FILE_NAME) : base;
    }

    private static Reason unreadableReason(PdfInspector.Status status) {
        return status == PdfInspector.Status.ENCRYPTED ? Reason.ENCRYPTED_PDF : Reason.BROKEN_PDF;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다.", e);
        }
    }

    private static boolean longerThan(String value, int max) {
        return value != null && value.length() > max;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static MaterialError error(String field, Reason reason, String detail) {
        return new MaterialError(field, reason, detail);
    }
}
