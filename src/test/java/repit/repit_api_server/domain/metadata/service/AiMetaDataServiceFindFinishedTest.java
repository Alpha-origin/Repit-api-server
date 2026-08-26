package repit.repit_api_server.domain.metadata.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import repit.repit_api_server.domain.metadata.dto.response.CallbackSuccessResponse;
import repit.repit_api_server.domain.metadata.entity.AnalysisDataEntity;
import repit.repit_api_server.domain.metadata.entity.enums.AnalysisStatus;
import repit.repit_api_server.domain.metadata.repository.AnalysisDataRepository;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 구독이 붙는 순간 되짚어 보낼지를 가린다.
 *
 * <p>이 판정이 헐거우면 결과가 저장된 적 없는 행까지 완료로 나가, 클라이언트는 구독하자마자
 * 분석이 끝났다는 신호를 받고 조회했다가 빈손이 된다.
 */
@ExtendWith(MockitoExtension.class)
class AiMetaDataServiceFindFinishedTest {

    @Mock
    private AnalysisDataRepository analysisDataRepository;

    private AiMetaDataService service() {
        return new AiMetaDataService(analysisDataRepository);
    }

    private void given(String jobId, AnalysisStatus status, Object result) {
        when(analysisDataRepository.findById(jobId)).thenReturn(Optional.of(AnalysisDataEntity.builder()
                .jobId(jobId)
                .status(status)
                .result(result)
                .build()));
    }

    @Test
    void 결과가_저장된_성공은_되짚어_보낸다() {
        given("job-1", AnalysisStatus.SUCCEEDED, Map.of("project_summary", "요약"));

        CallbackSuccessResponse finished = service().findFinished("job-1");

        assertThat(finished).isNotNull();
        assertThat(finished.getStatus()).isEqualTo("succeeded");
        assertThat(finished.getResult()).isEqualTo(Map.of("project_summary", "요약"));
    }

    /** 결과가 저장되기 전에 완료를 흘려보내면 클라이언트는 끝난 줄 알고 빈 결과를 집어 든다. */
    @Test
    void 결과가_없는_성공은_아직_끝난_것으로_보지_않는다() {
        given("job-2", AnalysisStatus.SUCCEEDED, null);

        assertThat(service().findFinished("job-2")).isNull();
    }

    /** 실패는 결과가 없다는 것이 확정된 상태다. 막으면 실패한 분석이 타임아웃까지 매달린다. */
    @Test
    void 실패는_결과가_없어도_되짚어_보낸다() {
        given("job-3", AnalysisStatus.FAILED, null);

        CallbackSuccessResponse finished = service().findFinished("job-3");

        assertThat(finished).isNotNull();
        assertThat(finished.getStatus()).isEqualTo("failed");
    }

    @Test
    void 아직_진행_중인_작업은_되짚지_않는다() {
        given("job-4", AnalysisStatus.PENDING, null);

        assertThat(service().findFinished("job-4")).isNull();
    }

    @Test
    void 모르는_작업은_되짚지_않는다() {
        when(analysisDataRepository.findById("job-5")).thenReturn(Optional.empty());

        assertThat(service().findFinished("job-5")).isNull();
    }
}
