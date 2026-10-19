package repit.repit_api_server.domain.metadata.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import repit.repit_api_server.domain.metadata.dto.request.MaterialsPayload;
import repit.repit_api_server.domain.metadata.dto.request.MaterialsPayload.DocumentInput;
import repit.repit_api_server.domain.metadata.dto.request.MaterialsPayload.CoverLetterInput;
import repit.repit_api_server.domain.metadata.dto.request.MaterialsPayload.SupplementInput;
import repit.repit_api_server.domain.metadata.dto.response.MaterialErrorResponse.MaterialError;
import repit.repit_api_server.domain.metadata.dto.response.MaterialErrorResponse.Reason;
import repit.repit_api_server.domain.metadata.dto.response.MaterialsResponse;
import repit.repit_api_server.domain.metadata.dto.response.MaterialsResponse.MaterialDocument;
import repit.repit_api_server.domain.metadata.exception.MaterialValidationException;
import repit.repit_api_server.domain.metadata.service.MaterialsValidator.ValidatedMaterials;
import repit.repit_api_server.domain.metadata.support.JobRole;
import repit.repit_api_server.domain.metadata.support.MaterialFileStorage;
import repit.repit_api_server.domain.metadata.support.MaterialFileStorage.Download;
import repit.repit_api_server.domain.metadata.support.MaterialFileStorage.DownloadStatus;
import repit.repit_api_server.domain.metadata.support.PdfInspector;
import repit.repit_api_server.domain.metadata.support.TestPdfs;
import repit.repit_api_server.global.exception.BusinessException;
import repit.repit_api_server.global.exception.ExternalApiException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 직무·자료 저장 규칙(비개발 직군 명세 01 §2.1, 06 §3.4). PDF는 실제로 만들어 PDFBox로 연다. */
@ExtendWith(MockitoExtension.class)
class MaterialsValidatorTest {

    private static final MaterialsResponse NOTHING_SAVED = MaterialsResponse.empty();
    private static final String SAVED_URL = "https://bucket/saved.pdf";

    @Mock
    private MaterialFileStorage storage;

    private MaterialsValidator validator() {
        return new MaterialsValidator(new PdfInspector(), storage);
    }

    private final Map<String, MultipartFile> files = new HashMap<>();

    private DocumentInput newDoc(String type, String fileKey, byte[] pdf) {
        files.put(fileKey, new MockMultipartFile(fileKey, fileKey + ".pdf", "application/pdf", pdf));
        return new DocumentInput(type, "NEW", fileKey, null);
    }

    private static DocumentInput existingDoc(String type, String fileUrl) {
        return new DocumentInput(type, "EXISTING", null, fileUrl);
    }

    private static MaterialsPayload payload(String jobRole, List<DocumentInput> documents, List<String> gitUrls,
                                            List<SupplementInput> supplements) {
        return new MaterialsPayload(jobRole, documents, gitUrls, supplements);
    }

    private static MaterialsResponse saved(String jobRole, Integer pageCount) {
        return new MaterialsResponse(jobRole,
                List.of(new MaterialDocument(0, "PORTFOLIO", SAVED_URL, "saved.pdf", pageCount)), List.of(), List.of());
    }

    private List<MaterialError> errorsOf(MaterialsPayload payload, MaterialsResponse saved) {
        try {
            validator().validate(payload, files, saved);
        } catch (MaterialValidationException e) {
            return e.getErrors();
        }
        throw new AssertionError("검증을 통과하면 안 된다");
    }

    @Test
    void 기획자_새_문서와_보완_입력은_통과하고_문서_순서_쪽수_파일명이_남는다() {
        MaterialsPayload request = payload("PRODUCT_MANAGER",
                List.of(newDoc("PRD", "file0", TestPdfs.pdf(9, "prd")), newDoc("POLICY_DOC", "file1", TestPdfs.pdf(6, "policy"))),
                List.of(),
                List.of(new SupplementInput("회원가입 개편", 0, "이탈 줄이기", "정책 정리", "단계 축소", null),
                        new SupplementInput(" ", null, "", null, null, null)));

        ValidatedMaterials result = validator().validate(request, files, NOTHING_SAVED);

        assertThat(result.jobRole()).isEqualTo(JobRole.PRODUCT_MANAGER);
        assertThat(result.documents()).extracting("type", "fileName", "pageCount")
                .containsExactly(tuple("PRD", "file0.pdf", 9), tuple("POLICY_DOC", "file1.pdf", 6));
        assertThat(result.documents()).allMatch(MaterialsValidator.ValidDocument::isNew);
        // 내용이 없는 프로젝트는 저장하지 않는다.
        assertThat(result.supplements()).extracting(SupplementInput::title).containsExactly("회원가입 개편");
    }

    @Test
    void 직무가_없거나_모르는_값이면_다른_규칙은_보지_않고_JOB_ROLE_REQUIRED() {
        assertThat(errorsOf(payload(null, List.of(), List.of(), List.of()), NOTHING_SAVED))
                .containsExactly(new MaterialError("jobRole", Reason.JOB_ROLE_REQUIRED, null));
        assertThat(errorsOf(payload("DESIGNER", List.of(), List.of(), List.of()), NOTHING_SAVED))
                .extracting(MaterialError::reason).containsExactly(Reason.JOB_ROLE_REQUIRED);
    }

    @Test
    void 문서가_없으면_NO_DOCUMENT_6개면_TOO_MANY_DOCUMENTS() {
        assertThat(errorsOf(payload("QA_ENGINEER", List.of(), List.of(), List.of()), NOTHING_SAVED))
                .containsExactly(new MaterialError("documents", Reason.NO_DOCUMENT, null));

        List<DocumentInput> six = Collections.nCopies(6, new DocumentInput("TEST_PLAN", "NEW", "file0", null));
        assertThat(errorsOf(payload("QA_ENGINEER", six, List.of(), List.of()), NOTHING_SAVED))
                .containsExactly(new MaterialError("documents", Reason.TOO_MANY_DOCUMENTS, null));
    }

    @Test
    void 직무가_받지_않는_종류와_필수_종류_없음() {
        List<MaterialError> errors = errorsOf(payload("BACKEND",
                List.of(newDoc("TEST_CASE", "file0", TestPdfs.pdf(1, "a"))), List.of("https://github.com/a/b"), List.of()),
                NOTHING_SAVED);

        assertThat(errors).extracting(MaterialError::field, MaterialError::reason).containsExactly(
                tuple("documents[0]", Reason.DOC_TYPE_NOT_ALLOWED),
                tuple("documents", Reason.REQUIRED_DOC_MISSING));
    }

    @Test
    void 이력서만으로는_필수_조건을_채우지_못한다() {
        List<MaterialError> errors = errorsOf(payload("QA_ENGINEER",
                List.of(newDoc("RESUME", "file0", TestPdfs.pdf(2, "resume"))), List.of(), List.of()), NOTHING_SAVED);

        assertThat(errors).containsExactly(
                new MaterialError("documents", Reason.REQUIRED_DOC_MISSING, "TEST_PLAN,TEST_CASE"));
    }

    @Test
    void PDF가_아니거나_손상되거나_암호가_걸리면_그_행에_사유를_단다() {
        files.put("file0", new MockMultipartFile("file0", "plan.txt", "text/plain", "hello".getBytes()));
        files.put("file1", new MockMultipartFile("file1", "case.pdf", "application/pdf", "not a pdf".getBytes()));
        files.put("file2", new MockMultipartFile("file2", "locked.pdf", "application/pdf", TestPdfs.encryptedPdf()));

        List<MaterialError> errors = errorsOf(payload("QA_ENGINEER", List.of(
                new DocumentInput("TEST_PLAN", "NEW", "file0", null),
                new DocumentInput("TEST_CASE", "NEW", "file1", null),
                new DocumentInput("BUG_REPORT", "NEW", "file2", null)), List.of(), List.of()), NOTHING_SAVED);

        assertThat(errors).extracting(MaterialError::field, MaterialError::reason).containsExactly(
                tuple("documents[0]", Reason.NOT_PDF),
                tuple("documents[1]", Reason.BROKEN_PDF),
                tuple("documents[2]", Reason.ENCRYPTED_PDF));
    }

    @Test
    void 빈_PDF는_400이_아니라_그_행에_BROKEN_PDF를_달고_다른_행도_검사한다() {
        files.put("file0", new MockMultipartFile("file0", "empty.pdf", "application/pdf", new byte[0]));
        files.put("file1", new MockMultipartFile("file1", "case.txt", "text/plain", "hello".getBytes()));

        List<MaterialError> errors = errorsOf(payload("QA_ENGINEER", List.of(
                new DocumentInput("TEST_PLAN", "NEW", "file0", null),
                new DocumentInput("TEST_CASE", "NEW", "file1", null)), List.of(), List.of()), NOTHING_SAVED);

        assertThat(errors).extracting(MaterialError::field, MaterialError::reason).containsExactly(
                tuple("documents[0]", Reason.BROKEN_PDF),
                tuple("documents[1]", Reason.NOT_PDF));
    }

    @Test
    void PDF를_읽다_Error가_나도_만든_임시_파일을_남기지_않는다() throws Exception {
        long before = countMaterialTempFiles();
        PdfInspector exploding = mock(PdfInspector.class);
        OutOfMemoryError failure = new OutOfMemoryError("PDF가 너무 복잡하다");
        when(exploding.inspect(any())).thenThrow(failure);
        newDoc("TEST_PLAN", "file0", TestPdfs.pdf(2, "plan"));

        assertThatThrownBy(() -> new MaterialsValidator(exploding, storage).validate(payload("QA_ENGINEER",
                List.of(new DocumentInput("TEST_PLAN", "NEW", "file0", null)), List.of(), List.of()), files, NOTHING_SAVED))
                .isSameAs(failure);
        assertThat(countMaterialTempFiles()).isEqualTo(before);
    }

    /** 잠깐의 S3 장애를 "저장된 문서 없음"으로 답하면 사용자가 멀쩡한 문서를 지운다. 장애는 그대로 올려 503으로 나가게 한다. */
    @Test
    void 쪽수를_세려고_옛_PDF를_받다가_S3가_잠깐_실패하면_EXISTING_NOT_FOUND가_아니라_그_장애를_던진다() {
        ExternalApiException unavailable = new ExternalApiException("저장된 자료를 잠시 읽지 못했습니다.",
                HttpStatus.SERVICE_UNAVAILABLE, null);
        when(storage.download(SAVED_URL, MaterialsValidator.MAX_FILE_BYTES)).thenThrow(unavailable);

        assertThatThrownBy(() -> validator().validate(payload("BACKEND",
                List.of(existingDoc("PORTFOLIO", SAVED_URL)), List.of("https://github.com/a/b"), List.of()),
                files, saved(null, null)))
                .isSameAs(unavailable);
    }

    @Test
    void 파일_하나가_50MB를_넘으면_옮기지_않고_FILE_TOO_LARGE() throws Exception {
        MultipartFile huge = mock(MultipartFile.class);
        when(huge.getOriginalFilename()).thenReturn("huge.pdf");
        when(huge.getContentType()).thenReturn("application/pdf");
        when(huge.getSize()).thenReturn(MaterialsValidator.MAX_FILE_BYTES + 1);
        files.put("file0", huge);

        List<MaterialError> errors = errorsOf(payload("QA_ENGINEER",
                List.of(new DocumentInput("TEST_PLAN", "NEW", "file0", null)), List.of(), List.of()), NOTHING_SAVED);

        assertThat(errors).extracting(MaterialError::reason).containsExactly(Reason.FILE_TOO_LARGE);
        verify(huge, never()).transferTo(any(Path.class));
    }

    @Test
    void 같은_파일을_두_번_올리면_두_번째_행이_DUPLICATE_FILE() {
        byte[] same = TestPdfs.pdf(3, "same");
        List<MaterialError> errors = errorsOf(payload("QA_ENGINEER",
                List.of(newDoc("TEST_PLAN", "file0", same), newDoc("TEST_CASE", "file1", same)), List.of(), List.of()),
                NOTHING_SAVED);

        assertThat(errors).containsExactly(new MaterialError("documents[1]", Reason.DUPLICATE_FILE, null));
    }

    @Test
    void 쪽수_합계가_50쪽을_넘으면_합계를_담아_TOO_MANY_PAGES() {
        List<MaterialError> errors = errorsOf(payload("QA_ENGINEER",
                List.of(newDoc("TEST_PLAN", "file0", TestPdfs.pdf(6, "a")), newDoc("TEST_CASE", "file1", TestPdfs.pdf(46, "b"))),
                List.of(), List.of()), NOTHING_SAVED);

        assertThat(errors).containsExactly(new MaterialError("documents", Reason.TOO_MANY_PAGES, "52"));
    }

    @Test
    void 다른_직군으로_바꾸며_저장된_문서를_보내면_MATERIALS_RESET_REQUIRED() {
        List<MaterialError> errors = errorsOf(payload("SECURITY",
                List.of(existingDoc("PORTFOLIO", SAVED_URL), newDoc("CTF_WRITEUP", "file0", TestPdfs.pdf(2, "ctf"))),
                List.of(), List.of()), saved("QA_ENGINEER", 4));

        assertThat(errors).containsExactly(new MaterialError("documents[0]", Reason.MATERIALS_RESET_REQUIRED, null));
    }

    @Test
    void 개발_3직무_사이에서는_저장된_문서를_그대로_쓰고_저장된_쪽수를_쓴다() {
        ValidatedMaterials result = validator().validate(payload("FRONTEND",
                List.of(existingDoc("PORTFOLIO", SAVED_URL)), List.of("https://github.com/a/web"), List.of()),
                files, saved("BACKEND", 18));

        assertThat(result.documents()).singleElement().satisfies(document -> {
            assertThat(document.isNew()).isFalse();
            assertThat(document.fileUrl()).isEqualTo(SAVED_URL);
            assertThat(document.fileName()).isEqualTo("saved.pdf");
            assertThat(document.pageCount()).isEqualTo(18);
        });
        verify(storage, never()).download(eq(SAVED_URL), anyLong());
    }

    @Test
    void 저장된_적_없는_주소를_EXISTING으로_보내면_EXISTING_NOT_FOUND() {
        List<MaterialError> errors = errorsOf(payload("BACKEND",
                List.of(existingDoc("PORTFOLIO", "https://bucket/other.pdf")), List.of("https://github.com/a/b"), List.of()),
                saved("BACKEND", 3));

        assertThat(errors).containsExactly(new MaterialError("documents[0]", Reason.EXISTING_NOT_FOUND, null));
    }

    @Test
    void 직무가_없던_옛_사용자는_백엔드로_보고_개발_직무에서_옛_PDF를_쓰며_쪽수를_지금_센다() throws Exception {
        Path legacyFile = Files.write(Files.createTempFile("legacy-", ".pdf"), TestPdfs.pdf(7, "legacy"));
        when(storage.download(SAVED_URL, MaterialsValidator.MAX_FILE_BYTES))
                .thenReturn(new Download(DownloadStatus.OK, legacyFile));

        ValidatedMaterials result = validator().validate(payload("BACKEND",
                List.of(existingDoc("PORTFOLIO", SAVED_URL)), List.of("https://github.com/a/b"), List.of()),
                files, saved(null, null));

        assertThat(result.documents()).singleElement().extracting("pageCount").isEqualTo(7);
        // 쪽수만 세고 받은 임시 파일은 바로 지운다.
        assertThat(legacyFile).doesNotExist();
    }

    @Test
    void 쪽수를_세야_하는_옛_PDF가_50MB를_넘으면_받지_않고_FILE_TOO_LARGE() {
        when(storage.download(SAVED_URL, MaterialsValidator.MAX_FILE_BYTES))
                .thenReturn(new Download(DownloadStatus.TOO_LARGE, null));

        List<MaterialError> errors = errorsOf(payload("BACKEND",
                List.of(existingDoc("PORTFOLIO", SAVED_URL)), List.of("https://github.com/a/b"), List.of()), saved(null, null));

        assertThat(errors).containsExactly(new MaterialError("documents[0]", Reason.FILE_TOO_LARGE, null));
    }

    @Test
    void 새_파일_합계가_200MB를_넘으면_하나도_옮기지_않고_FILE_TOO_LARGE() throws Exception {
        List<MultipartFile> parts = new java.util.ArrayList<>();
        List<DocumentInput> inputs = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            MultipartFile part = mock(MultipartFile.class);
            when(part.getSize()).thenReturn(45L * 1024 * 1024);
            files.put("file" + i, part);
            parts.add(part);
            inputs.add(new DocumentInput("TEST_PLAN", "NEW", "file" + i, null));
        }

        List<MaterialError> errors = errorsOf(payload("QA_ENGINEER", inputs, List.of(), List.of()), NOTHING_SAVED);

        assertThat(errors).containsExactly(
                new MaterialError("documents", Reason.FILE_TOO_LARGE, String.valueOf(5 * 45L * 1024 * 1024)));
        for (MultipartFile part : parts) {
            verify(part, never()).transferTo(any(Path.class));
        }
    }

    @Test
    void 문서_목록이_가리키지_않는_파일_파트가_있으면_400() {
        newDoc("TEST_PLAN", "file0", TestPdfs.pdf(1, "plan"));
        files.put("file1", new MockMultipartFile("file1", "extra.pdf", "application/pdf", TestPdfs.pdf(1, "extra")));
        files.put("payload", new MockMultipartFile("payload", "blob", "application/json", "{}".getBytes()));

        assertThatThrownBy(() -> validator().validate(payload("QA_ENGINEER",
                List.of(new DocumentInput("TEST_PLAN", "NEW", "file0", null)), List.of(), List.of()), files, NOTHING_SAVED))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("file1");
    }

    @Test
    void 직무가_없던_옛_사용자도_비개발로_바꾸면_옛_PDF를_쓸_수_없다() {
        List<MaterialError> errors = errorsOf(payload("DATA_ANALYST",
                List.of(existingDoc("PORTFOLIO", SAVED_URL)), List.of(), List.of()), saved(null, null));

        assertThat(errors).containsExactly(new MaterialError("documents[0]", Reason.MATERIALS_RESET_REQUIRED, null));
    }

    @Test
    void GitHub_규칙_필수_숨김_형식() {
        byte[] portfolio = TestPdfs.pdf(1, "p");
        assertThat(errorsOf(payload("BACKEND", List.of(newDoc("PORTFOLIO", "file0", portfolio)), List.of(), List.of()),
                NOTHING_SAVED)).containsExactly(new MaterialError("gitUrls", Reason.GITHUB_REQUIRED, null));

        assertThat(errorsOf(payload("PRODUCT_MANAGER", List.of(newDoc("PRD", "file0", portfolio)),
                List.of("https://github.com/a/b"), List.of()), NOTHING_SAVED))
                .containsExactly(new MaterialError("gitUrls", Reason.GITHUB_NOT_ALLOWED, null));

        assertThat(errorsOf(payload("QA_ENGINEER", List.of(newDoc("TEST_PLAN", "file0", portfolio)),
                List.of("https://github.com/a/qa", "gitlab.com/a/qa", "kim"), List.of()), NOTHING_SAVED))
                .containsExactly(new MaterialError("gitUrls[1]", Reason.INVALID_GITHUB_URL, null),
                        new MaterialError("gitUrls[2]", Reason.INVALID_GITHUB_URL, null));
    }

    @Test
    void GitHub_주소는_저장소와_계정_주소를_받고_앞뒤_공백을_지운다() {
        ValidatedMaterials result = validator().validate(payload("DATA_ANALYST",
                List.of(newDoc("ANALYSIS_REPORT", "file0", TestPdfs.pdf(1, "r"))),
                List.of(" https://github.com/alpha/churn-analysis ", "github.com/alpha", "https://www.github.com/a/b.git/"),
                List.of()), files, NOTHING_SAVED);

        assertThat(result.gitUrls()).containsExactly(
                "https://github.com/alpha/churn-analysis", "github.com/alpha", "https://www.github.com/a/b.git/");
    }

    @Test
    void 보완_입력은_기획자_컨설턴트만_받고_길이와_문서_번호를_본다() {
        byte[] pdf = TestPdfs.pdf(1, "x");
        assertThat(errorsOf(payload("QA_ENGINEER", List.of(newDoc("TEST_PLAN", "file0", pdf)), List.of(),
                List.of(new SupplementInput("프로젝트", null, null, null, null, null))), NOTHING_SAVED))
                .containsExactly(new MaterialError("supplements", Reason.SUPPLEMENT_NOT_ALLOWED, null));

        assertThat(errorsOf(payload("PRODUCT_MANAGER", List.of(newDoc("PRD", "file0", pdf)), List.of(), List.of(
                new SupplementInput("가".repeat(81), null, null, null, null, null),
                new SupplementInput("ok", 3, null, null, null, null),
                new SupplementInput("ok", null, "가".repeat(501), null, null, null))), NOTHING_SAVED))
                .extracting(MaterialError::field).containsExactly("supplements[0]", "supplements[1]", "supplements[2]");
    }

    @Test
    void 걸린_규칙은_모아서_한_번에_돌려준다() {
        files.put("file0", new MockMultipartFile("file0", "locked.pdf", "application/pdf", TestPdfs.encryptedPdf()));

        List<MaterialError> errors = errorsOf(payload("PRODUCT_MANAGER",
                List.of(new DocumentInput("PRD", "NEW", "file0", null)), List.of("https://github.com/a/b"), List.of()),
                NOTHING_SAVED);

        assertThat(errors).extracting(MaterialError::reason)
                .containsExactly(Reason.ENCRYPTED_PDF, Reason.GITHUB_NOT_ALLOWED);
    }

    @Test
    void 요청에_없는_파일_파트를_가리키거나_출처가_틀리면_400() {
        assertThatThrownBy(() -> validator().validate(payload("QA_ENGINEER",
                List.of(new DocumentInput("TEST_PLAN", "NEW", "file3", null)), List.of(), List.of()), files, NOTHING_SAVED))
                .isInstanceOf(BusinessException.class)
                .extracting("status").hasToString("400 BAD_REQUEST");

        assertThatThrownBy(() -> validator().validate(payload("QA_ENGINEER",
                List.of(new DocumentInput("TEST_PLAN", "UPLOAD", null, null)), List.of(), List.of()), files, NOTHING_SAVED))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void payload_파트를_파일로_가리켜도_PDF로_읽지_않고_400() {
        files.put("payload", new MockMultipartFile("payload", "blob", "application/json", "{}".getBytes()));

        assertThatThrownBy(() -> validator().validate(payload("QA_ENGINEER",
                List.of(new DocumentInput("TEST_PLAN", "NEW", "payload", null)), List.of(), List.of()), files, NOTHING_SAVED))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 통과하면_새_파일은_임시_파일로_남고_deleteTempFiles로_지워진다() {
        ValidatedMaterials result = validator().validate(payload("QA_ENGINEER",
                List.of(newDoc("TEST_PLAN", "file0", TestPdfs.pdf(2, "plan"))), List.of(), List.of()), files, NOTHING_SAVED);

        Path temp = result.documents().get(0).file();
        assertThat(temp).exists();
        result.deleteTempFiles();
        assertThat(temp).doesNotExist();
    }

    @Test
    void 검사에_걸리면_만든_임시_파일을_남기지_않는다() throws Exception {
        long before = countMaterialTempFiles();

        errorsOf(payload("QA_ENGINEER", List.of(newDoc("TEST_PLAN", "file0", TestPdfs.pdf(2, "plan"))),
                List.of("gitlab.com/a/b"), List.of()), NOTHING_SAVED);

        assertThat(countMaterialTempFiles()).isEqualTo(before);
    }

    @Test
    void 같은_저장소를_가리키는_GitHub_주소는_처음_것_하나만_남긴다() {
        ValidatedMaterials result = validator().validate(payload("BACKEND",
                List.of(newDoc("PORTFOLIO", "file0", TestPdfs.pdf(1, "p"))),
                List.of("https://github.com/alpha/api", "github.com/Alpha/API/", "https://www.github.com/alpha/api.git",
                        "https://github.com/alpha/batch"),
                List.of()), files, NOTHING_SAVED);

        assertThat(result.gitUrls()).containsExactly("https://github.com/alpha/api", "https://github.com/alpha/batch");
    }

    @Test
    void GitHub_주소는_서로_다른_것_10개까지_받는다() {
        List<String> eleven = java.util.stream.IntStream.range(0, 11)
                .mapToObj(i -> "https://github.com/alpha/repo" + i).toList();

        assertThat(errorsOf(payload("BACKEND", List.of(newDoc("PORTFOLIO", "file0", TestPdfs.pdf(1, "p"))), eleven, List.of()),
                NOTHING_SAVED)).containsExactly(new MaterialError("gitUrls", Reason.INVALID_GITHUB_URL, "TOO_MANY"));
    }

    @Test
    void GitHub_기능_경로는_계정_주소로_받지_않는다() {
        List<MaterialError> errors = errorsOf(payload("DATA_ANALYST",
                List.of(newDoc("ANALYSIS_REPORT", "file0", TestPdfs.pdf(1, "r"))),
                List.of("https://github.com/settings", "github.com/orgs/alpha", "https://github.com/alpha/settings"),
                List.of()), NOTHING_SAVED);

        // 계정 자리가 기능 경로인 것만 막는다. 저장소 이름이 settings인 것은 받는다.
        assertThat(errors).extracting(MaterialError::field).containsExactly("gitUrls[0]", "gitUrls[1]");
    }

    private static long countMaterialTempFiles() throws Exception {
        try (var stream = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return stream.filter(path -> path.getFileName().toString().startsWith("repit-material-")).count();
        }
    }
    private static MaterialsPayload general(String title, List<DocumentInput> documents, List<CoverLetterInput> letters) {
        return new MaterialsPayload("GEN_MARKETING", documents, List.of(), List.of(), title, letters);
    }

    @Test
    void 일반_직군은_자소서만으로_저장하고_공백과_빈_항목을_정리한다() {
        ValidatedMaterials result = validator().validate(general(" 브랜드 마케터 ", List.of(), List.of(
                new CoverLetterInput(" ", null), new CoverLetterInput(" 동기 ", " 경험 "))), files, NOTHING_SAVED);

        assertThat(result.jobTitle()).isEqualTo("브랜드 마케터");
        assertThat(result.documents()).isEmpty();
        assertThat(result.coverLetters()).containsExactly(new CoverLetterInput("동기", "경험"));
    }

    @Test
    void 일반_직군은_업무_산출물이나_경력기술서만으로도_저장한다() {
        for (String type : List.of("WORK_SAMPLE", "CAREER_DESCRIPTION")) {
            ValidatedMaterials result = validator().validate(general("마케터",
                    List.of(newDoc(type, "file0", TestPdfs.pdf(1, type))), List.of()), files, NOTHING_SAVED);
            assertThat(result.documents()).extracting(MaterialsValidator.ValidDocument::type).containsExactly(type);
            result.deleteTempFiles();
        }
    }

    @Test
    void 일반_직군은_이력서만_있거나_모두_비면_MATERIAL_REQUIRED만_반환한다() {
        assertThat(errorsOf(general("마케터", List.of(), List.of()), NOTHING_SAVED))
                .containsExactly(new MaterialError("materials", Reason.MATERIAL_REQUIRED, null));
        assertThat(errorsOf(general("마케터", List.of(newDoc("RESUME", "file0", TestPdfs.pdf(1, "resume"))),
                List.of()), NOTHING_SAVED))
                .containsExactly(new MaterialError("materials", Reason.MATERIAL_REQUIRED, null));
    }

    @Test
    void 일반_직무명은_필수이며_길이와_제어문자를_검사한다() {
        List<CoverLetterInput> letters = List.of(new CoverLetterInput("동기", "경험"));
        for (String title : java.util.Arrays.asList(null, "", " ")) {
            assertThat(errorsOf(general(title, List.of(), letters), NOTHING_SAVED))
                    .containsExactly(new MaterialError("jobTitle", Reason.JOB_TITLE_REQUIRED, null));
        }
        for (String title : List.of("가".repeat(31), "브랜드\n마케터", "브랜드\t마케터")) {
            assertThat(errorsOf(general(title, List.of(), letters), NOTHING_SAVED))
                    .containsExactly(new MaterialError("jobTitle", Reason.JOB_TITLE_INVALID, null));
        }
        assertThat(validator().validate(general("가".repeat(30), List.of(), letters), files, NOTHING_SAVED).jobTitle())
                .hasSize(30);
    }

    @Test
    void 자소서_오류는_원래_위치에_남기고_저장조건_오류_다음에_반환한다() {
        assertThat(errorsOf(general("마케터", List.of(), List.of(new CoverLetterInput(" ", " "),
                new CoverLetterInput(null, "답만"))), NOTHING_SAVED))
                .containsExactly(new MaterialError("materials", Reason.MATERIAL_REQUIRED, null),
                        new MaterialError("coverLetters[1]", Reason.COVER_LETTER_INVALID, null));
        assertThat(errorsOf(general("마케터", List.of(), Collections.nCopies(6,
                new CoverLetterInput("문항", "답"))), NOTHING_SAVED))
                .containsExactly(new MaterialError("materials", Reason.MATERIAL_REQUIRED, null),
                        new MaterialError("coverLetters", Reason.COVER_LETTER_INVALID, null));
        for (CoverLetterInput letter : List.of(new CoverLetterInput("가".repeat(201), "답"),
                new CoverLetterInput("문항", "가".repeat(2001)), new CoverLetterInput("문항", ""))) {
            assertThat(errorsOf(general("마케터", List.of(), List.of(letter)), NOTHING_SAVED))
                    .extracting(MaterialError::reason)
                    .containsExactly(Reason.MATERIAL_REQUIRED, Reason.COVER_LETTER_INVALID);
        }
        assertThat(validator().validate(general("마케터", List.of(), List.of(
                new CoverLetterInput("가".repeat(200), "가".repeat(2000)))), files, NOTHING_SAVED).coverLetters())
                .hasSize(1);
    }

    @Test
    void 일반_검증_오류는_직무명_문서_저장조건_GitHub_보완_자소서_순서다() {
        MaterialsPayload request = new MaterialsPayload("GEN_MARKETING",
                List.of(newDoc("TEST_CASE", "file0", TestPdfs.pdf(1, "a"))),
                List.of("https://github.com/a/b"), List.of(new SupplementInput("프로젝트", null, null, null, null, null)),
                null, List.of(new CoverLetterInput("문항", "")));
        assertThat(errorsOf(request, NOTHING_SAVED)).extracting(MaterialError::reason)
                .containsExactly(Reason.JOB_TITLE_REQUIRED, Reason.DOC_TYPE_NOT_ALLOWED,
                        Reason.GITHUB_NOT_ALLOWED, Reason.SUPPLEMENT_NOT_ALLOWED, Reason.COVER_LETTER_INVALID);
    }

    @Test
    void IT_직군은_직무명_자소서_업무산출물을_받지_않는다() {
        MaterialsPayload request = new MaterialsPayload("QA_ENGINEER",
                List.of(newDoc("TEST_PLAN", "file0", TestPdfs.pdf(1, "a")),
                        newDoc("WORK_SAMPLE", "file1", TestPdfs.pdf(1, "b"))), List.of(), List.of(), "테스터",
                List.of(new CoverLetterInput("문항", "답")));
        assertThat(errorsOf(request, NOTHING_SAVED)).extracting(MaterialError::reason)
                .containsExactly(Reason.JOB_TITLE_NOT_ALLOWED, Reason.DOC_TYPE_NOT_ALLOWED, Reason.COVER_LETTER_NOT_ALLOWED);
    }

    @Test
    void 일반_대분류_변경은_기존문서를_거절하고_같은_대분류는_허용한다() {
        MaterialsResponse saved = new MaterialsResponse("GEN_MARKETING",
                List.of(new MaterialDocument(0, "WORK_SAMPLE", SAVED_URL, "work.pdf", 1)),
                List.of(), List.of(), "마케터", List.of());
        MaterialsPayload request = general("브랜드 마케터", List.of(existingDoc("WORK_SAMPLE", SAVED_URL)), List.of());
        assertThat(validator().validate(request, files, saved).documents()).hasSize(1);
        MaterialsPayload changed = new MaterialsPayload("GEN_HR_ADMIN", request.documents(), List.of(), List.of(),
                "채용 담당", List.of());
        assertThat(errorsOf(changed, saved)).extracting(MaterialError::reason)
                .containsExactly(Reason.MATERIALS_RESET_REQUIRED);
    }
}
