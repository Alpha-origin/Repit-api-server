package repit.repit_api_server.domain.metadata.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import repit.repit_api_server.domain.metadata.dto.request.GenerateRequest;
import repit.repit_api_server.domain.metadata.dto.response.GenerateResponse;
import repit.repit_api_server.domain.metadata.dto.response.MetaDataResponse;
import repit.repit_api_server.domain.metadata.service.AiMetaDataService;
import repit.repit_api_server.domain.metadata.service.MetaService;
import repit.repit_api_server.domain.metadata.sse.SseEmitterRepository;
import repit.repit_api_server.global.client.AiServerClient;
import repit.repit_api_server.global.client.AuthServerClient;
import repit.repit_api_server.global.response.UserResponse;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 분석 접수는 분석 서버 응답을 받아야 할 수 있는데, 그 응답이 콜백보다 늦게 오는 일이 있다.
 * 소유자 조회까지 그 뒤에 두면 소유자가 더 늦게 붙고, 그 사이 콜백이 만든 행은 주인 없이 남는다.
 * 주인 없는 분석으로는 면접이 열리지 않으므로 순서를 고정한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiMetaDataControllerGenerateTest {

    @Mock
    private MetaService metaService;
    @Mock
    private AiMetaDataService aiMetaDataService;
    @Mock
    private AiServerClient aiServerClient;
    @Mock
    private AuthServerClient authServerClient;

    private AiMetaDataController controller;

    @BeforeEach
    void setUp() {
        controller = new AiMetaDataController(
                metaService, aiMetaDataService, new SseEmitterRepository(), aiServerClient, authServerClient);
        ReflectionTestUtils.setField(controller, "callbackBaseUrl", "http://localhost:8080");

        when(metaService.getMetaData("Bearer token")).thenReturn(MetaDataResponse.builder()
                .fileUrl("https://s3/portfolio.pdf")
                .gitUrls(List.of("https://github.com/user/repo"))
                .build());
        when(aiServerClient.generate(any(GenerateRequest.class))).thenReturn(GenerateResponse.builder()
                .job_id("job-1")
                .status("accepted")
                .build());
    }

    private UserResponse user(Long id) {
        UserResponse user = mock(UserResponse.class);
        when(user.getId()).thenReturn(id);
        return user;
    }

    @Test
    void 소유자를_분석_서버에_요청하기_전에_확인한다() {
        UserResponse owner = user(9L);
        when(authServerClient.getUser("Bearer token")).thenReturn(owner);

        controller.generate("Bearer token");

        InOrder order = inOrder(authServerClient, aiServerClient, aiMetaDataService);
        order.verify(authServerClient).getUser("Bearer token");
        order.verify(aiServerClient).generate(any(GenerateRequest.class));
        order.verify(aiMetaDataService).registerJob(eq("job-1"), eq(9L), any(LocalDateTime.class));
    }

    /** 소유자를 못 찾아도 분석은 시작한다. 여기서 막으면 분석 자체를 못 한다. */
    @Test
    void 소유자를_확인하지_못해도_접수는_진행한다() {
        when(authServerClient.getUser("Bearer token")).thenReturn(null);

        controller.generate("Bearer token");

        verify(aiMetaDataService).registerJob(eq("job-1"), eq(null), any(LocalDateTime.class));
    }

    @Test
    void 사용자_조회가_터져도_분석은_시작한다() {
        when(authServerClient.getUser("Bearer token")).thenThrow(new RuntimeException("인증 서버 장애"));

        controller.generate("Bearer token");

        verify(aiMetaDataService).registerJob(eq("job-1"), eq(null), any(LocalDateTime.class));
    }
}
