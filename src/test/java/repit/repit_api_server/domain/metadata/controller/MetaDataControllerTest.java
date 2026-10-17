package repit.repit_api_server.domain.metadata.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import repit.repit_api_server.domain.metadata.dto.response.MaterialsResponse;
import repit.repit_api_server.domain.metadata.dto.response.MetaDataResponse;
import repit.repit_api_server.domain.metadata.service.AnalysisLaunchService;
import repit.repit_api_server.domain.metadata.service.MaterialsService;
import repit.repit_api_server.domain.metadata.service.MetaService;
import repit.repit_api_server.global.auth.AuthUser;
import repit.repit_api_server.global.exception.BusinessException;
import repit.repit_api_server.global.response.UserResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 자료를 올리지 않은 사용자에게 인증 서버는 두 값을 null로 준다. 그것을 500이 아니라 false로 답한다.
 */
@ExtendWith(MockitoExtension.class)
class MetaDataControllerTest {

    private static final AuthUser USER = new AuthUser(new UserResponse(), "Bearer token");

    @Mock
    private MetaService metaService;
    @Mock
    private AnalysisLaunchService analysisLaunchService;
    @Mock
    private MaterialsService materialsService;
    @InjectMocks
    private MetaDataController controller;

    @Test
    void 자료가_없으면_false() {
        when(metaService.getMetaData("Bearer token")).thenReturn(new MetaDataResponse(null, null));

        assertThat(controller.isGit(USER).getBody()).isFalse();
        assertThat(controller.isPortfolio(USER).getBody()).isFalse();
    }

    @Test
    void 인증_서버가_빈_응답을_주면_false() {
        when(metaService.getMetaData("Bearer token")).thenReturn(null);

        assertThat(controller.isGit(USER).getBody()).isFalse();
        assertThat(controller.isPortfolio(USER).getBody()).isFalse();
    }

    @Test
    void 자료가_있으면_true() {
        when(metaService.getMetaData("Bearer token"))
                .thenReturn(new MetaDataResponse(List.of("https://github.com/a/b"), "https://s3/x.pdf"));

        assertThat(controller.isGit(USER).getBody()).isTrue();
        assertThat(controller.isPortfolio(USER).getBody()).isTrue();
    }

    @Test
    void 저장된_직무와_자료를_사용자_토큰으로_받아_그대로_돌려준다() {
        MaterialsResponse materials = new MaterialsResponse("QA_ENGINEER",
                List.of(new MaterialsResponse.MaterialDocument(0, "TEST_PLAN", "https://s3/plan.pdf", "plan.pdf", 12)),
                List.of(), List.of());
        when(metaService.getMaterials("Bearer token")).thenReturn(materials);

        assertThat(controller.getMaterials(USER).getBody()).isSameAs(materials);
    }

    @Test
    void 분석할_수_없는_직무면_옛_업로드도_자료를_저장하기_전에_422() throws Exception {
        doThrow(BusinessException.unprocessable("비개발 직무는 아직 자료 분석을 지원하지 않습니다."))
                .when(analysisLaunchService).requireSupportedJob(any());

        assertThatThrownBy(() -> controller.upload(USER,
                new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[]{1}), List.of("https://github.com/a/b")))
                .isInstanceOf(BusinessException.class);
        verify(metaService, never()).dataUpload(any(), any(), any());
    }
}
