package repit.repit_api_server.domain.metadata.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MultipartFile;
import repit.repit_api_server.domain.metadata.dto.request.MaterialsPayload;
import repit.repit_api_server.domain.metadata.dto.response.MaterialErrorResponse.MaterialError;
import repit.repit_api_server.domain.metadata.dto.response.MaterialErrorResponse.Reason;
import repit.repit_api_server.domain.metadata.dto.response.MaterialsResponse;
import repit.repit_api_server.domain.metadata.dto.response.MaterialsSaveResponse;
import repit.repit_api_server.domain.metadata.exception.MaterialValidationException;
import repit.repit_api_server.domain.metadata.service.AnalysisLaunchService;
import repit.repit_api_server.domain.metadata.service.MaterialsService;
import repit.repit_api_server.domain.metadata.service.MetaService;
import repit.repit_api_server.global.auth.AuthUser;
import repit.repit_api_server.global.error.GlobalExceptionHandler;
import repit.repit_api_server.global.response.UserResponse;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web이 보내는 multipart(payload JSON 파트 + file0~file4)가 그대로 서비스까지 가는지, 200·422 응답 모양이
 * 계약 C-1과 같은지 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class MetaDataControllerMaterialsPutTest {

    private static final String PAYLOAD = """
            {"jobRole": "PRODUCT_MANAGER",
             "documents": [{"type": "PRD", "source": "NEW", "fileKey": "file0"},
                           {"type": "POLICY_DOC", "source": "EXISTING", "fileUrl": "https://bucket/c3d4.pdf"}],
             "gitUrls": [],
             "supplements": [{"title": "회원가입 개편", "documentIndex": 0, "goal": "이탈 줄이기"}]}
            """;

    @Mock
    private MetaService metaService;
    @Mock
    private AnalysisLaunchService analysisLaunchService;
    @Mock
    private MaterialsService materialsService;

    private MockMvc mockMvc;
    private AuthUser authUser;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new MetaDataController(metaService, analysisLaunchService, materialsService))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        UserResponse user = new UserResponse();
        ReflectionTestUtils.setField(user, "id", 7L);
        authUser = new AuthUser(user, "Bearer token");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(authUser, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static MockMultipartFile payloadPart() {
        return new MockMultipartFile("payload", "", MediaType.APPLICATION_JSON_VALUE, PAYLOAD.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @SuppressWarnings("unchecked")
    void payload와_파일_파트를_받아_저장하고_자료와_분석_접수를_돌려준다() throws Exception {
        when(materialsService.replace(eq(authUser), any(), any())).thenReturn(new MaterialsSaveResponse(
                new MaterialsResponse("PRODUCT_MANAGER",
                        List.of(new MaterialsResponse.MaterialDocument(0, "PRD", "https://bucket/a1b2.pdf", "PRD.pdf", 9)),
                        List.of(), List.of()),
                new MaterialsSaveResponse.Analysis("job-9", "accepted")));

        mockMvc.perform(multipart("/api/v1/metaData/materials")
                        .file(payloadPart())
                        .file(new MockMultipartFile("file0", "PRD.pdf", "application/pdf", new byte[]{1, 2, 3}))
                        .with(request -> {
                            request.setMethod("PUT");
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.materials.jobRole").value("PRODUCT_MANAGER"))
                .andExpect(jsonPath("$.materials.documents[0].index").value(0))
                .andExpect(jsonPath("$.materials.documents[0].pageCount").value(9))
                .andExpect(jsonPath("$.analysis.jobId").value("job-9"))
                .andExpect(jsonPath("$.analysis.status").value("accepted"));

        ArgumentCaptor<MaterialsPayload> payload = ArgumentCaptor.forClass(MaterialsPayload.class);
        ArgumentCaptor<Map<String, MultipartFile>> files = ArgumentCaptor.forClass(Map.class);
        verify(materialsService).replace(eq(authUser), payload.capture(), files.capture());
        assertThat(payload.getValue().jobRole()).isEqualTo("PRODUCT_MANAGER");
        assertThat(payload.getValue().documents()).containsExactly(
                new MaterialsPayload.DocumentInput("PRD", "NEW", "file0", null),
                new MaterialsPayload.DocumentInput("POLICY_DOC", "EXISTING", null, "https://bucket/c3d4.pdf"));
        assertThat(payload.getValue().supplements()).singleElement()
                .satisfies(s -> assertThat(s.documentIndex()).isZero());
        // payload 파트도 파일 맵에 섞여 온다. 검증기가 file0~file4만 파일로 읽는다.
        assertThat(files.getValue()).containsKey("file0");
        assertThat(files.getValue().get("file0").getOriginalFilename()).isEqualTo("PRD.pdf");
    }

    @Test
    void 바뀐_것이_없으면_analysis가_null로_나간다() throws Exception {
        when(materialsService.replace(eq(authUser), any(), any()))
                .thenReturn(new MaterialsSaveResponse(MaterialsResponse.empty(), null));

        mockMvc.perform(multipart("/api/v1/metaData/materials").file(payloadPart()).with(request -> {
                    request.setMethod("PUT");
                    return request;
                }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysis").doesNotExist());
    }

    @Test
    void 규칙에_걸리면_422와_사유_목록이_나간다() throws Exception {
        when(materialsService.replace(eq(authUser), any(), any())).thenThrow(new MaterialValidationException(List.of(
                new MaterialError("documents[1]", Reason.ENCRYPTED_PDF, null),
                new MaterialError("documents", Reason.TOO_MANY_PAGES, "52"))));

        mockMvc.perform(multipart("/api/v1/metaData/materials").file(payloadPart()).with(request -> {
                    request.setMethod("PUT");
                    return request;
                }))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value("자료를 저장하지 못했습니다."))
                .andExpect(jsonPath("$.errors[0].field").value("documents[1]"))
                .andExpect(jsonPath("$.errors[0].reason").value("ENCRYPTED_PDF"))
                .andExpect(jsonPath("$.errors[0].detail").doesNotExist())
                .andExpect(jsonPath("$.errors[1].reason").value("TOO_MANY_PAGES"))
                .andExpect(jsonPath("$.errors[1].detail").value("52"));
    }
    @Test
    void 일반_직무명과_자소서_payload를_받고_응답에_함께_싣는다() throws Exception {
        when(materialsService.replace(eq(authUser), any(), any())).thenReturn(new MaterialsSaveResponse(
                new MaterialsResponse("GEN_MARKETING", List.of(), List.of(), List.of(), "퍼포먼스 마케터",
                        List.of(new MaterialsResponse.CoverLetter("역량", "캠페인 경험"))),
                new MaterialsSaveResponse.Analysis("job-general", "accepted")));
        String payload = """
                {"jobRole":"GEN_MARKETING","jobTitle":"퍼포먼스 마케터","documents":[],
                 "gitUrls":[],"supplements":[],"coverLetters":[{"question":"역량","answer":"캠페인 경험"}]}
                """;
        mockMvc.perform(multipart("/api/v1/metaData/materials").file(new MockMultipartFile(
                        "payload", "", MediaType.APPLICATION_JSON_VALUE, payload.getBytes(StandardCharsets.UTF_8)))
                        .with(request -> { request.setMethod("PUT"); return request; }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.materials.jobTitle").value("퍼포먼스 마케터"))
                .andExpect(jsonPath("$.materials.coverLetters[0].answer").value("캠페인 경험"));
        var sent = ArgumentCaptor.forClass(MaterialsPayload.class);
        verify(materialsService).replace(eq(authUser), sent.capture(), any());
        assertThat(sent.getValue().jobTitle()).isEqualTo("퍼포먼스 마케터");
        assertThat(sent.getValue().coverLetters()).containsExactly(new MaterialsPayload.CoverLetterInput("역량", "캠페인 경험"));
    }

}
