package repit.repit_api_server.domain.metadata.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import repit.repit_api_server.domain.metadata.dto.request.GenerateRequest;
import repit.repit_api_server.domain.metadata.dto.response.GenerateResponse;
import repit.repit_api_server.domain.metadata.dto.response.MetaDataResponse;
import repit.repit_api_server.global.client.AiServerClient;
import repit.repit_api_server.global.client.AuthServerClient;
import repit.repit_api_server.global.exception.ExternalApiException;
import repit.repit_api_server.global.response.UserResponse;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 분석은 자료를 올리는 길과 분석만 다시 요청하는 길 양쪽에서 시작된다.
 * 어느 길로 시작하든 접수까지 함께 되어야 그 분석에 주인이 남는다.
 * 주인 없는 분석으로는 면접이 열리지 않는다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AnalysisLaunchServiceTest {

    @Mock
    private AiServerClient aiServerClient;
    @Mock
    private AuthServerClient authServerClient;
    @Mock
    private AiMetaDataService aiMetaDataService;

    private AnalysisLaunchService service;
    // 스터빙을 미리 끝내둔다. when(...) 안에서 다시 스터빙하면 미완성 스터빙으로 걸린다.
    private UserResponse owner;

    @BeforeEach
    void setUp() {
        owner = mock(UserResponse.class);
        when(owner.getId()).thenReturn(9L);

        service = new AnalysisLaunchService(aiServerClient, authServerClient, aiMetaDataService);
        ReflectionTestUtils.setField(service, "callbackBaseUrl", "https://api.repit.test");

        when(aiServerClient.generate(any(GenerateRequest.class))).thenReturn(GenerateResponse.builder()
                .job_id("job-1")
                .status("accepted")
                .build());
        when(aiServerClient.generateMock(any(GenerateRequest.class))).thenReturn(GenerateResponse.builder()
                .job_id("mock-job-1")
                .status("accepted")
                .build());
    }

    private MetaDataResponse metaData() {
        return MetaDataResponse.builder()
                .fileUrl("https://s3/portfolio.pdf")
                .gitUrls(List.of("https://github.com/user/repo"))
                .build();
    }

    @Test
    void 분석을_요청하고_그_실행을_접수한다() {
        when(authServerClient.getUser("Bearer token")).thenReturn(owner);

        GenerateResponse response = service.launch("Bearer token", metaData());

        assertThat(response.getJob_id()).isEqualTo("job-1");
        verify(aiMetaDataService).registerJob(eq("job-1"), eq(9L), any(LocalDateTime.class));
    }

    /**
     * 접수는 분석 서버가 job_id를 돌려줘야 할 수 있는데, 그 응답이 콜백보다 늦게 오는 일이 있다.
     * 소유자 조회까지 뒤에 두면 그 사이 콜백이 만든 행이 주인 없이 남는다.
     */
    @Test
    void 소유자를_분석_서버에_요청하기_전에_확인한다() {
        when(authServerClient.getUser("Bearer token")).thenReturn(owner);

        service.launch("Bearer token", metaData());

        InOrder order = inOrder(authServerClient, aiServerClient, aiMetaDataService);
        order.verify(authServerClient).getUser("Bearer token");
        order.verify(aiServerClient).generate(any(GenerateRequest.class));
        order.verify(aiMetaDataService).registerJob(eq("job-1"), eq(9L), any(LocalDateTime.class));
    }

    /** 콜백 주소를 설정에서 만든다. 박아두면 주소가 바뀐 순간 콜백이 영영 오지 않는다. */
    @Test
    void 콜백_주소를_설정에서_만든다() {
        when(authServerClient.getUser("Bearer token")).thenReturn(owner);
        ArgumentCaptor<GenerateRequest> sent = ArgumentCaptor.forClass(GenerateRequest.class);

        service.launch("Bearer token", metaData());

        verify(aiServerClient).generate(sent.capture());
        assertThat(sent.getValue().getCallback_url()).isEqualTo("https://api.repit.test/api/v1/ai/callback");
        assertThat(sent.getValue().getPortfolio_url()).isEqualTo("https://s3/portfolio.pdf");
        assertThat(sent.getValue().getGithub_urls()).containsExactly("https://github.com/user/repo");
    }

    /** 소유자를 못 찾아도 접수는 한다. 걷어내지 않으면 지난 실행 결과가 완료로 새어나간다. */
    @Test
    void 소유자를_확인하지_못해도_접수는_한다() {
        when(authServerClient.getUser("Bearer token")).thenReturn(null);

        service.launch("Bearer token", metaData());

        verify(aiMetaDataService).registerJob(eq("job-1"), eq(null), any(LocalDateTime.class));
    }

    @Test
    void 사용자_조회가_터져도_분석은_시작한다() {
        when(authServerClient.getUser("Bearer token")).thenThrow(new RuntimeException("인증 서버 장애"));

        service.launch("Bearer token", metaData());

        verify(aiMetaDataService).registerJob(eq("job-1"), eq(null), any(LocalDateTime.class));
    }

    /** job_id가 없으면 구독도 조회도 할 수 없다. 성공으로 돌려주면 원인을 찾을 수 없다. */
    @Test
    void job_id가_없으면_실패로_돌린다() {
        when(authServerClient.getUser("Bearer token")).thenReturn(owner);
        when(aiServerClient.generate(any(GenerateRequest.class))).thenReturn(GenerateResponse.builder()
                .status("rejected")
                .build());

        assertThatThrownBy(() -> service.launch("Bearer token", metaData()))
                .isInstanceOf(ExternalApiException.class);
    }

    /** 접수가 실패해도 요청은 성공시킨다. 여기서 터지면 클라이언트가 jobId를 받지 못한다. */
    @Test
    void 접수에_실패해도_jobId는_돌려준다() {
        when(authServerClient.getUser("Bearer token")).thenReturn(owner);
        doThrowOnRegister();

        GenerateResponse response = service.launch("Bearer token", metaData());

        assertThat(response.getJob_id()).isEqualTo("job-1");
    }

    private void doThrowOnRegister() {
        org.mockito.Mockito.doThrow(new RuntimeException("DB 장애"))
                .when(aiMetaDataService).registerJob(any(), any(), any());
    }

    @Test
    void mock_분석도_같은_접수를_거친다() {
        when(authServerClient.getUser("Bearer token")).thenReturn(owner);

        service.launchMock("Bearer token", metaData());

        verify(aiMetaDataService).registerJob(eq("mock-job-1"), eq(9L), any(LocalDateTime.class));
    }
}
