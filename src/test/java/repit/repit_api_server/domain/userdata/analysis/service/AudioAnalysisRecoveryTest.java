package repit.repit_api_server.domain.userdata.analysis.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import repit.repit_api_server.domain.userdata.analysis.repository.AudioAnalysisRepository;
import repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackDispatchKind;
import repit.repit_api_server.domain.userdata.feedback.repository.FeedbackDispatchRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 결과도 실패도 아닌 채 멈춘 음성 분석을 다시 확인하게 되돌리는지.
 *
 * <p>접수가 성공하면 대기 행은 닫힌다. 음성 분석은 접수가 아니라 결과 콜백이 끝이라, 콜백이 유실되면
 * 아무도 그 요청을 다시 보지 않는다. 되돌려 놓지 않으면 작업 조회로 결과를 가져오는 코드가 정작 그
 * 상황에서 돌지 않는다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AudioAnalysisRecoveryTest {

    @Mock
    private AudioAnalysisRepository analysisRepository;
    @Mock
    private FeedbackDispatchRepository dispatchRepository;

    private AudioAnalysisRecovery recovery;

    @BeforeEach
    void setUp() {
        recovery = new AudioAnalysisRecovery(analysisRepository, dispatchRepository);
        ReflectionTestUtils.setField(recovery, "pendingTimeout", Duration.ofMinutes(10));
        when(dispatchRepository.reopen(anyLong(), any(), any(), anyString())).thenReturn(1);
    }

    @Test
    void 멈춘_요청이_남은_면접을_다시_기다리는_자리로_되돌린다() {
        when(analysisRepository.findUnresolvedInterviewIds(any())).thenReturn(List.of(42L, 43L));

        recovery.sweep();

        verify(dispatchRepository).reopen(eq(42L), eq(FeedbackDispatchKind.AUDIO_ANALYSIS), any(), anyString());
        verify(dispatchRepository).reopen(eq(43L), eq(FeedbackDispatchKind.AUDIO_ANALYSIS), any(), anyString());
    }

    /** 유예 시간이 지난 것으로 찍어야 스윕이 곧바로 집어 간다. 지금 시각으로 찍으면 다음 유예만큼 더 기다린다. */
    @Test
    void 되돌릴_때_활동_시각은_유예가_지난_시점으로_찍는다() {
        when(analysisRepository.findUnresolvedInterviewIds(any())).thenReturn(List.of(42L));

        recovery.sweep();

        ArgumentCaptor<LocalDateTime> lastActivityAt = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(dispatchRepository).reopen(eq(42L), any(), lastActivityAt.capture(), anyString());
        assertThat(lastActivityAt.getValue()).isBefore(LocalDateTime.now().minusMinutes(9));
    }

    /** 아직 기다릴 때가 되지 않은 요청까지 되돌리면, 결과가 오는 중인 작업을 헛되게 다시 확인한다. */
    @Test
    void 확인할_때가_된_요청만_찾는다() {
        when(analysisRepository.findUnresolvedInterviewIds(any())).thenReturn(List.of());

        recovery.sweep();

        ArgumentCaptor<LocalDateTime> checkBefore = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(analysisRepository).findUnresolvedInterviewIds(checkBefore.capture());
        assertThat(checkBefore.getValue()).isBefore(LocalDateTime.now().minusMinutes(9));
        verify(dispatchRepository, never()).reopen(anyLong(), any(), any(), anyString());
    }

    /** 한 건이 넘어져도 나머지는 되돌려야 한다. 여기서 멈추면 그 뒤 면접이 모두 매달린다. */
    @Test
    void 한_면접이_실패해도_나머지를_되돌린다() {
        when(analysisRepository.findUnresolvedInterviewIds(any())).thenReturn(List.of(42L, 43L));
        when(dispatchRepository.reopen(eq(42L), any(), any(), anyString()))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatCode(() -> recovery.sweep()).doesNotThrowAnyException();

        verify(dispatchRepository).reopen(eq(43L), eq(FeedbackDispatchKind.AUDIO_ANALYSIS), any(), anyString());
    }
}
