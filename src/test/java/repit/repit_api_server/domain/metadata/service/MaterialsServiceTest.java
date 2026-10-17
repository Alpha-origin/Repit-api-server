package repit.repit_api_server.domain.metadata.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;
import repit.repit_api_server.domain.metadata.dto.request.AuthMaterialsRequest;
import repit.repit_api_server.domain.metadata.dto.request.MaterialsPayload;
import repit.repit_api_server.domain.metadata.dto.request.MaterialsPayload.DocumentInput;
import repit.repit_api_server.domain.metadata.dto.request.MaterialsPayload.SupplementInput;
import repit.repit_api_server.domain.metadata.dto.response.AuthMaterialsResponse;
import repit.repit_api_server.domain.metadata.dto.response.AuthMaterialsResponse.DocumentEntry;
import repit.repit_api_server.domain.metadata.dto.response.AuthMaterialsResponse.SupplementEntry;
import repit.repit_api_server.domain.metadata.dto.response.GenerateResponse;
import repit.repit_api_server.domain.metadata.dto.response.MaterialsSaveResponse;
import repit.repit_api_server.domain.metadata.dto.response.MetaDataResponse;
import repit.repit_api_server.domain.metadata.exception.MaterialValidationException;
import repit.repit_api_server.domain.metadata.support.MaterialFileStorage;
import repit.repit_api_server.domain.metadata.support.PdfInspector;
import repit.repit_api_server.domain.metadata.support.TestPdfs;
import repit.repit_api_server.global.auth.AuthUser;
import repit.repit_api_server.global.client.AuthServerClient;
import repit.repit_api_server.global.exception.ExternalApiException;
import repit.repit_api_server.global.response.UserResponse;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MaterialsServiceTest {

    private static final String TOKEN = "Bearer token";
    private static final String SAVED_URL = "https://bucket/saved.pdf";

    @Mock
    private AuthServerClient authServerClient;
    @Mock
    private MaterialFileStorage storage;
    @Mock
    private AnalysisLaunchService analysisLaunchService;

    private MaterialsService service;
    private AuthUser authUser;

    @BeforeEach
    void setUp() {
        service = new MaterialsService(authServerClient, new MaterialsValidator(new PdfInspector(), storage),
                storage, analysisLaunchService);
        UserResponse user = new UserResponse();
        ReflectionTestUtils.setField(user, "id", 7L);
        authUser = new AuthUser(user, TOKEN);
    }

    private static AuthMaterialsResponse savedBackend() {
        return new AuthMaterialsResponse("MAJOR_BACKEND",
                List.of(new DocumentEntry("PORTFOLIO", SAVED_URL, "saved.pdf", 12)),
                List.of("https://github.com/a/api"), List.of(), null);
    }

    @Test
    void 같은_내용이면_저장도_분석_요청도_하지_않고_analysis는_null() {
        when(authServerClient.getMaterials(TOKEN)).thenReturn(savedBackend());

        MaterialsSaveResponse response = service.replace(authUser, new MaterialsPayload("BACKEND",
                List.of(new DocumentInput("PORTFOLIO", "EXISTING", null, SAVED_URL)),
                List.of("https://github.com/a/api"), List.of()), Map.of());

        assertThat(response.analysis()).isNull();
        assertThat(response.materials().jobRole()).isEqualTo("BACKEND");
        verify(authServerClient, never()).putMaterials(anyString(), any());
        verifyNoInteractions(analysisLaunchService);
    }

    @Test
    void 개발_직무끼리_바꾸면_자료는_그대로_두고_새_직무로_저장해_분석을_다시_요청한다() {
        when(authServerClient.getMaterials(TOKEN)).thenReturn(savedBackend());
        when(authServerClient.putMaterials(eq(TOKEN), any())).thenAnswer(invocation -> {
            AuthMaterialsRequest request = invocation.getArgument(1);
            return new AuthMaterialsResponse(request.major(), request.documents(), request.gitUrls(), request.supplements(), null);
        });
        when(analysisLaunchService.launch(eq(7L), eq("MAJOR_FRONTEND"), any()))
                .thenReturn(new GenerateResponse("job-1", "processing", null));

        MaterialsSaveResponse response = service.replace(authUser, new MaterialsPayload("FRONTEND",
                List.of(new DocumentInput("PORTFOLIO", "EXISTING", null, SAVED_URL)),
                List.of("https://github.com/a/api"), List.of()), Map.of());

        assertThat(response.materials().jobRole()).isEqualTo("FRONTEND");
        assertThat(response.analysis()).isEqualTo(new MaterialsSaveResponse.Analysis("job-1", "accepted"));
        verify(storage, never()).upload(any());
    }

    @Test
    void 새_파일은_올린_뒤_요청_순서대로_인증_서버에_저장하고_첫_문서로_분석을_요청한다() {
        when(authServerClient.getMaterials(TOKEN)).thenReturn(new AuthMaterialsResponse(null, List.of(), List.of(), List.of(), null));
        when(storage.upload(any())).thenReturn("https://bucket/prd.pdf", "https://bucket/policy.pdf");
        when(authServerClient.putMaterials(eq(TOKEN), any())).thenAnswer(invocation -> {
            AuthMaterialsRequest request = invocation.getArgument(1);
            return new AuthMaterialsResponse(request.major(), request.documents(), request.gitUrls(), request.supplements(), null);
        });
        when(analysisLaunchService.launch(eq(7L), eq("MAJOR_PRODUCT_MANAGER"), any()))
                .thenReturn(new GenerateResponse("job-2", "processing", null));
        Map<String, MultipartFile> files = Map.of(
                "file0", new MockMultipartFile("file0", "회원가입-PRD.pdf", "application/pdf", TestPdfs.pdf(9, "prd")),
                "file1", new MockMultipartFile("file1", "정책서.pdf", "application/pdf", TestPdfs.pdf(6, "policy")));

        MaterialsSaveResponse response = service.replace(authUser, new MaterialsPayload("PRODUCT_MANAGER",
                List.of(new DocumentInput("PRD", "NEW", "file0", null), new DocumentInput("POLICY_DOC", "NEW", "file1", null)),
                null, List.of(new SupplementInput("회원가입 개편", 0, "이탈 줄이기", null, null, null))), files);

        ArgumentCaptor<AuthMaterialsRequest> sent = ArgumentCaptor.forClass(AuthMaterialsRequest.class);
        verify(authServerClient).putMaterials(eq(TOKEN), sent.capture());
        assertThat(sent.getValue()).isEqualTo(new AuthMaterialsRequest("MAJOR_PRODUCT_MANAGER",
                List.of(new DocumentEntry("PRD", "https://bucket/prd.pdf", "회원가입-PRD.pdf", 9),
                        new DocumentEntry("POLICY_DOC", "https://bucket/policy.pdf", "정책서.pdf", 6)),
                List.of(),
                List.of(new SupplementEntry("회원가입 개편", 0, "이탈 줄이기", null, null, null))));

        ArgumentCaptor<MetaDataResponse> launched = ArgumentCaptor.forClass(MetaDataResponse.class);
        verify(analysisLaunchService).launch(eq(7L), eq("MAJOR_PRODUCT_MANAGER"), launched.capture());
        assertThat(launched.getValue().getFileUrl()).isEqualTo("https://bucket/prd.pdf");
        assertThat(launched.getValue().getGitUrls()).isEmpty();

        assertThat(response.materials().documents()).extracting("index", "fileName", "pageCount")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(0, "회원가입-PRD.pdf", 9),
                        org.assertj.core.groups.Tuple.tuple(1, "정책서.pdf", 6));
        assertThat(response.analysis().jobId()).isEqualTo("job-2");
    }

    @Test
    void 인증_서버_저장이_실패하면_방금_올린_파일을_지우고_실패를_그대로_던진다() {
        when(authServerClient.getMaterials(TOKEN)).thenReturn(savedBackend());
        when(storage.upload(any())).thenReturn("https://bucket/new.pdf");
        ExternalApiException failure = new ExternalApiException("인증 서버에 오류가 발생했습니다.", HttpStatus.BAD_GATEWAY, null);
        when(authServerClient.putMaterials(eq(TOKEN), any())).thenThrow(failure);
        Map<String, MultipartFile> files = Map.of(
                "file0", new MockMultipartFile("file0", "new.pdf", "application/pdf", TestPdfs.pdf(2, "new")));

        assertThatThrownBy(() -> service.replace(authUser, new MaterialsPayload("BACKEND",
                List.of(new DocumentInput("PORTFOLIO", "NEW", "file0", null)),
                List.of("https://github.com/a/api"), List.of()), files))
                .isSameAs(failure);

        verify(storage).deleteQuietly("https://bucket/new.pdf");
        verifyNoInteractions(analysisLaunchService);
    }

    @Test
    void 검사에_걸리면_파일을_올리지도_저장하지도_않는다() {
        when(authServerClient.getMaterials(TOKEN)).thenReturn(savedBackend());

        assertThatThrownBy(() -> service.replace(authUser, new MaterialsPayload("BACKEND",
                List.of(new DocumentInput("PORTFOLIO", "EXISTING", null, SAVED_URL)), List.of(), List.of()), Map.of()))
                .isInstanceOf(MaterialValidationException.class);

        verify(storage, never()).upload(any());
        verify(authServerClient, never()).putMaterials(anyString(), any());
    }

    @Test
    void 저장은_끝났는데_분석_요청이_실패하면_저장은_되돌리지_않고_analysis_status는_failed() {
        when(authServerClient.getMaterials(TOKEN)).thenReturn(savedBackend());
        when(authServerClient.putMaterials(eq(TOKEN), any())).thenAnswer(invocation -> {
            AuthMaterialsRequest request = invocation.getArgument(1);
            return new AuthMaterialsResponse(request.major(), request.documents(), request.gitUrls(), request.supplements(), null);
        });
        when(analysisLaunchService.launch(eq(7L), anyString(), any()))
                .thenThrow(new ExternalApiException("분석 서버와 연결할 수 없습니다.", null, null));

        MaterialsSaveResponse response = service.replace(authUser, new MaterialsPayload("BACKEND",
                List.of(new DocumentInput("PORTFOLIO", "EXISTING", null, SAVED_URL)),
                List.of("https://github.com/a/api", "https://github.com/a/batch"), List.of()), Map.of());

        assertThat(response.analysis()).isEqualTo(new MaterialsSaveResponse.Analysis(null, "failed"));
        assertThat(response.materials().gitUrls()).hasSize(2);
        verify(storage, never()).deleteQuietly(anyString());
    }
}
