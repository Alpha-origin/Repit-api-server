package repit.repit_api_server.domain.metadata.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import repit.repit_api_server.domain.metadata.dto.response.MetaDataResponse;
import repit.repit_api_server.domain.metadata.service.AnalysisLaunchService;
import repit.repit_api_server.domain.metadata.service.MetaService;
import repit.repit_api_server.global.auth.AuthUser;
import repit.repit_api_server.global.response.UserResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
}
