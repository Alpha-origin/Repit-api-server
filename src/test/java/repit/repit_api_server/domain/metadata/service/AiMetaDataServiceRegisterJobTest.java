package repit.repit_api_server.domain.metadata.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import repit.repit_api_server.domain.metadata.entity.AnalysisDataEntity;
import repit.repit_api_server.domain.metadata.entity.enums.AnalysisStatus;
import repit.repit_api_server.domain.metadata.repository.AnalysisDataRepository;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 같은 jobId로 분석을 다시 요청했을 때, 지난 실행의 결과가 남아 있으면 구독이 붙는 순간
 * 분석 서버 콜백보다 먼저 완료 이벤트가 나간다. 접수 시점에 그 결과를 걷어내는지 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class AiMetaDataServiceRegisterJobTest {

    @Mock
    private AnalysisDataRepository analysisDataRepository;

    private AiMetaDataService service;

    @BeforeEach
    void setUp() {
        service = new AiMetaDataService(analysisDataRepository);
    }

    @Test
    void 지난_실행의_결과는_요청_시각을_기준으로_걷어낸다() {
        LocalDateTime requestedAt = LocalDateTime.now();
        when(analysisDataRepository.clearPreviousRun("job-1", AnalysisStatus.PENDING, requestedAt))
                .thenReturn(1);

        service.registerJob("job-1", 7L, requestedAt);

        // 이미 있던 행을 걷어냈으므로 새로 만들지 않는다. 만들면 받아둔 소유자와 생성 시각을 잃는다.
        verify(analysisDataRepository, never()).save(any());
        verify(analysisDataRepository).updateUserId("job-1", 7L);
    }

    // 분석 서버가 빠르면 요청 접수보다 콜백이 먼저 도착한다. 그 결과까지 지우면 안 된다.
    @Test
    void 요청보다_늦게_끝난_결과는_이번_실행의_것이라_지우지_않는다() {
        LocalDateTime requestedAt = LocalDateTime.now();
        when(analysisDataRepository.clearPreviousRun("job-2", AnalysisStatus.PENDING, requestedAt))
                .thenReturn(0);
        when(analysisDataRepository.existsById("job-2")).thenReturn(true);

        service.registerJob("job-2", 7L, requestedAt);

        verify(analysisDataRepository, never()).save(any());
        verify(analysisDataRepository).updateUserId("job-2", 7L);
    }

    @Test
    void 처음_보는_작업은_소유자와_함께_PENDING으로_만든다() {
        LocalDateTime requestedAt = LocalDateTime.now();
        when(analysisDataRepository.clearPreviousRun("job-3", AnalysisStatus.PENDING, requestedAt))
                .thenReturn(0);
        when(analysisDataRepository.existsById("job-3")).thenReturn(false);

        service.registerJob("job-3", 7L, requestedAt);

        ArgumentCaptor<AnalysisDataEntity> saved = ArgumentCaptor.forClass(AnalysisDataEntity.class);
        verify(analysisDataRepository).save(saved.capture());
        assertThat(saved.getValue().getJobId()).isEqualTo("job-3");
        assertThat(saved.getValue().getUserId()).isEqualTo(7L);
        assertThat(saved.getValue().getStatus()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(saved.getValue().getResult()).isNull();
        verify(analysisDataRepository, never()).updateUserId(eq("job-3"), any());
    }

    // 소유자를 확인하지 못했더라도 지난 결과는 걷어내야 한다. 안 그러면 옛 결과가 그대로 나간다.
    @Test
    void 소유자를_모르는_요청도_지난_결과는_걷어낸다() {
        LocalDateTime requestedAt = LocalDateTime.now();
        when(analysisDataRepository.clearPreviousRun("job-4", AnalysisStatus.PENDING, requestedAt))
                .thenReturn(1);

        service.registerJob("job-4", null, requestedAt);

        verify(analysisDataRepository).clearPreviousRun("job-4", AnalysisStatus.PENDING, requestedAt);
        // 소유자를 모르면 이미 기록된 소유자를 null로 덮지 않는다.
        verify(analysisDataRepository, never()).updateUserId(any(), any());
        verify(analysisDataRepository, never()).save(any());
    }
}
