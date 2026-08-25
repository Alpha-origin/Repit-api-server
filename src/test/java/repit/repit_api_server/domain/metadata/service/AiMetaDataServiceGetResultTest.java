package repit.repit_api_server.domain.metadata.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import repit.repit_api_server.domain.metadata.dto.response.ResultResponse;
import repit.repit_api_server.domain.metadata.entity.AnalysisDataEntity;
import repit.repit_api_server.domain.metadata.entity.enums.AnalysisStatus;
import repit.repit_api_server.domain.metadata.repository.AnalysisDataRepository;
import repit.repit_api_server.global.exception.BusinessException;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 채팅 서버는 이 응답 하나로 분석 결과를 판단한다.
 * result가 비었을 때 그 이유가 응답에 드러나는지 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class AiMetaDataServiceGetResultTest {

    @Mock
    private AnalysisDataRepository analysisDataRepository;

    private AiMetaDataService service;

    @BeforeEach
    void setUp() {
        service = new AiMetaDataService(analysisDataRepository);
    }

    @Test
    void 성공한_분석은_결과를_상태와_함께_돌려준다() {
        when(analysisDataRepository.findById("job-1")).thenReturn(Optional.of(AnalysisDataEntity.builder()
                .jobId("job-1")
                .userId(7L)
                .status(AnalysisStatus.SUCCEEDED)
                .result(Map.of("project_summary", "요약"))
                .build()));

        ResultResponse response = service.getResult("job-1");

        assertThat(response.getJobId()).isEqualTo("job-1");
        assertThat(response.getStatus()).isEqualTo("succeeded");
        assertThat(response.getResult()).isEqualTo(Map.of("project_summary", "요약"));
        assertThat(response.getError()).isNull();
    }

    // result만 보면 아직 분석 중인 것과 실패한 것이 구분되지 않는다.
    @Test
    void 아직_끝나지_않은_분석은_pending으로_알린다() {
        when(analysisDataRepository.findById("job-2")).thenReturn(Optional.of(AnalysisDataEntity.builder()
                .jobId("job-2")
                .userId(7L)
                .status(AnalysisStatus.PENDING)
                .build()));

        ResultResponse response = service.getResult("job-2");

        assertThat(response.getStatus()).isEqualTo("pending");
        assertThat(response.getResult()).isNull();
    }

    @Test
    void 실패한_분석은_사유를_함께_돌려준다() {
        when(analysisDataRepository.findById("job-3")).thenReturn(Optional.of(AnalysisDataEntity.builder()
                .jobId("job-3")
                .userId(7L)
                .status(AnalysisStatus.FAILED)
                .errorStatusCode(422)
                .errorMessage("PDF를 읽을 수 없습니다.")
                .build()));

        ResultResponse response = service.getResult("job-3");

        assertThat(response.getStatus()).isEqualTo("failed");
        assertThat(response.getResult()).isNull();
        assertThat(response.getError().getStatus_code()).isEqualTo(422);
        assertThat(response.getError().getMessage()).isEqualTo("PDF를 읽을 수 없습니다.");
    }

    // 잘못된 jobId를 200 + result: null로 돌려주면 호출자가 원인을 좇을 수 없다.
    @Test
    void 모르는_작업은_찾을_수_없다고_알린다() {
        when(analysisDataRepository.findById("없는-job")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getResult("없는-job"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("없는-job");
    }
}
