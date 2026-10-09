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
import repit.repit_api_server.domain.metadata.dto.request.ProfileRequest;
import repit.repit_api_server.domain.metadata.dto.response.GenerateResponse;
import repit.repit_api_server.domain.metadata.dto.response.MetaDataResponse;
import repit.repit_api_server.domain.metadata.entity.enums.AnalysisResultType;
import repit.repit_api_server.domain.metadata.repository.AnalysisDataRepository;
import repit.repit_api_server.global.auth.AuthUser;
import repit.repit_api_server.global.client.AiServerClient;
import repit.repit_api_server.global.response.UserResponse;
import repit.repit_api_server.global.exception.ExternalApiException;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    private AiMetaDataService aiMetaDataService;
    @Mock
    private MetaService metaService;
    @Mock
    private AnalysisDataRepository analysisDataRepository;

    /** 분석의 주인. 시큐리티 필터가 이미 확인한 사용자라 서비스는 id만 받는다. */
    private static final Long OWNER_ID = 9L;

    private AnalysisLaunchService service;

    @BeforeEach
    void setUp() {
        service = new AnalysisLaunchService(aiServerClient, aiMetaDataService, metaService, analysisDataRepository);
        ReflectionTestUtils.setField(service, "callbackBaseUrl", "https://api.repit.test");

        when(aiServerClient.requestProfile(any(ProfileRequest.class))).thenReturn(GenerateResponse.builder()
                .jobId("job-1")
                .status("accepted")
                .build());
        when(aiServerClient.generateMock(any(GenerateRequest.class))).thenReturn(GenerateResponse.builder()
                .jobId("mock-job-1")
                .status("accepted")
                .build());
    }

    private MetaDataResponse metaData() {
        return MetaDataResponse.builder()
                .fileUrl("https://s3/portfolio.pdf")
                .gitUrls(List.of("https://github.com/user/repo"))
                .build();
    }

    private AuthUser authUser() {
        UserResponse user = mock(UserResponse.class);
        when(user.getId()).thenReturn(OWNER_ID);
        when(user.getMajor()).thenReturn("MAJOR_BACKEND");
        return new AuthUser(user, "token-1");
    }

    @Test
    void 종합_데이터가_없는_사용자는_저장된_자료로_분석을_요청한다() {
        when(analysisDataRepository.existsByUserIdAndResultType(OWNER_ID, AnalysisResultType.PROFILE)).thenReturn(false);
        when(metaService.getMetaData("token-1")).thenReturn(metaData());

        service.launchIfMissing(authUser());

        ArgumentCaptor<ProfileRequest> sent = ArgumentCaptor.forClass(ProfileRequest.class);
        verify(aiServerClient).requestProfile(sent.capture());
        assertThat(sent.getValue().getPortfolioUrl()).isEqualTo("https://s3/portfolio.pdf");
        verify(aiMetaDataService).registerJob(eq("job-1"), eq(OWNER_ID), any(LocalDateTime.class), eq(AnalysisResultType.PROFILE));
    }

    @Test
    void 종합_데이터를_요청한_적이_있으면_다시_요청하지_않는다() {
        when(analysisDataRepository.existsByUserIdAndResultType(OWNER_ID, AnalysisResultType.PROFILE)).thenReturn(true);

        service.launchIfMissing(authUser());

        verify(metaService, never()).getMetaData(any());
        verify(aiServerClient, never()).requestProfile(any());
    }

    /** 면접 시작이 겹쳤다. 앞 요청이 잠근 채 분석을 접수했으니 뒤 요청은 다시 요청하지 않고 그 분석을 기다린다. */
    @Test
    void 잠그고_다시_보니_다른_요청이_분석을_요청했으면_다시_요청하지_않는다() {
        when(analysisDataRepository.existsByUserIdAndResultType(OWNER_ID, AnalysisResultType.PROFILE))
                .thenReturn(false, true);

        service.launchIfMissing(authUser());

        InOrder order = inOrder(analysisDataRepository);
        order.verify(analysisDataRepository).lockProfileLaunch(OWNER_ID);
        order.verify(analysisDataRepository).existsByUserIdAndResultType(OWNER_ID, AnalysisResultType.PROFILE);
        verify(metaService, never()).getMetaData(any());
        verify(aiServerClient, never()).requestProfile(any());
    }

    @Test
    void 저장된_자료가_모자라면_요청하지_않는다() {
        when(analysisDataRepository.existsByUserIdAndResultType(OWNER_ID, AnalysisResultType.PROFILE)).thenReturn(false);
        when(metaService.getMetaData("token-1")).thenReturn(MetaDataResponse.builder()
                .fileUrl("https://s3/portfolio.pdf")
                .gitUrls(List.of())
                .build());

        service.launchIfMissing(authUser());

        verify(aiServerClient, never()).requestProfile(any());
    }

    @Test
    void 분석을_요청하고_그_실행을_접수한다() {

        GenerateResponse response = service.launch(OWNER_ID, "MAJOR_BACKEND", metaData());

        assertThat(response.getJobId()).isEqualTo("job-1");
        verify(aiMetaDataService).registerJob(eq("job-1"), eq(9L), any(LocalDateTime.class), eq(AnalysisResultType.PROFILE));
    }

    /**
     * 접수는 분석 서버가 jobId를 돌려줘야 할 수 있는데, 그 응답이 콜백보다 늦게 오는 일이 있다.
     * 접수가 요청보다 앞설 수는 없지만, 그 사이에 도착한 콜백이 만든 행에 주인이 붙으려면
     * 접수는 요청 바로 뒤여야 한다.
     */
    @Test
    void 분석을_요청한_뒤에_곧바로_접수한다() {
        service.launch(OWNER_ID, "MAJOR_BACKEND", metaData());

        InOrder order = inOrder(aiServerClient, aiMetaDataService);
        order.verify(aiServerClient).requestProfile(any(ProfileRequest.class));
        order.verify(aiMetaDataService).registerJob(eq("job-1"), eq(9L), any(LocalDateTime.class), eq(AnalysisResultType.PROFILE));
    }

    /**
     * 콜백 주소를 설정에서 만든다. 박아두면 주소가 바뀐 순간 콜백이 영영 오지 않는다.
     * 옛 /generate 결과와 섞이지 않게 종합 데이터 전용 경로로 받는다.
     */
    @Test
    void 콜백_주소를_설정에서_만든다() {
        ArgumentCaptor<ProfileRequest> sent = ArgumentCaptor.forClass(ProfileRequest.class);

        service.launch(OWNER_ID, "MAJOR_BACKEND", metaData());

        verify(aiServerClient).requestProfile(sent.capture());
        assertThat(sent.getValue().getCallbackUrl()).isEqualTo("https://api.repit.test/api/v1/ai/profile/callback");
        assertThat(sent.getValue().getPortfolioUrl()).isEqualTo("https://s3/portfolio.pdf");
        assertThat(sent.getValue().getGithubUrls()).containsExactly("https://github.com/user/repo");
        assertThat(sent.getValue().getMajor()).isEqualTo("MAJOR_BACKEND");
    }

    /** jobId가 없으면 구독도 조회도 할 수 없다. 성공으로 돌려주면 원인을 찾을 수 없다. */
    @Test
    void jobId가_없으면_실패로_돌린다() {
        when(aiServerClient.requestProfile(any(ProfileRequest.class))).thenReturn(GenerateResponse.builder()
                .status("rejected")
                .build());

        assertThatThrownBy(() -> service.launch(OWNER_ID, null, metaData()))
                .isInstanceOf(ExternalApiException.class);
    }

    /** 접수가 실패해도 요청은 성공시킨다. 여기서 터지면 클라이언트가 jobId를 받지 못한다. */
    @Test
    void 접수에_실패해도_jobId는_돌려준다() {
        doThrowOnRegister();

        GenerateResponse response = service.launch(OWNER_ID, null, metaData());

        assertThat(response.getJobId()).isEqualTo("job-1");
    }

    private void doThrowOnRegister() {
        org.mockito.Mockito.doThrow(new RuntimeException("DB 장애"))
                .when(aiMetaDataService).registerJob(any(), any(), any(), any());
    }

    @Test
    void mock_분석도_같은_접수를_거친다() {

        service.launchMock(OWNER_ID, metaData());

        verify(aiMetaDataService).registerJob(eq("mock-job-1"), eq(9L), any(LocalDateTime.class),
                eq(AnalysisResultType.LEGACY_GENERATE));
    }
}
