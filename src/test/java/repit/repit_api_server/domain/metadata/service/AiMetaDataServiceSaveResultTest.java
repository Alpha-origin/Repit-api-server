package repit.repit_api_server.domain.metadata.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import repit.repit_api_server.domain.metadata.dto.request.CallbackSuccessRequest;
import repit.repit_api_server.domain.metadata.dto.response.CallbackSuccessResponse;
import repit.repit_api_server.domain.metadata.entity.AnalysisDataEntity;
import repit.repit_api_server.domain.metadata.entity.enums.AnalysisStatus;
import repit.repit_api_server.domain.metadata.repository.AnalysisDataRepository;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 콜백이 돌려주는 값은 요청을 되비춘 것이 아니라 DB에 남은 것이어야 한다.
 * 이 값이 그대로 구독으로 흘러가므로, 저장이 걸러낸 것이 여기 섞이면 클라이언트는
 * DB에 없는 완료를 받아들고 결과를 조회하다 빈손이 된다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiMetaDataServiceSaveResultTest {

    @Mock
    private AnalysisDataRepository analysisDataRepository;

    private AiMetaDataService service;

    @BeforeEach
    void setUp() {
        service = new AiMetaDataService(analysisDataRepository);
        when(analysisDataRepository.save(any(AnalysisDataEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void 저장한_성공_결과를_그대로_돌려준다() {
        when(analysisDataRepository.findById("job-1")).thenReturn(Optional.empty());

        CallbackSuccessResponse saved = service.saveResult(CallbackSuccessRequest.builder()
                .jobId("job-1")
                .status("succeeded")
                .result(Map.of("project_summary", "요약"))
                .build());

        assertThat(saved.getJobId()).isEqualTo("job-1");
        assertThat(saved.getStatus()).isEqualTo("succeeded");
        assertThat(saved.getResult()).isEqualTo(Map.of("project_summary", "요약"));
        assertThat(saved.getError()).isNull();
    }

    /** 어느 작업의 결과인지 알 수 없으면 저장할 곳도, 흘려보낼 구독도 없다. */
    @Test
    void jobId가_없으면_저장도_반환도_없다() {
        CallbackSuccessResponse saved = service.saveResult(CallbackSuccessRequest.builder()
                .status("succeeded")
                .result(Map.of("project_summary", "요약"))
                .build());

        assertThat(saved).isNull();
        verify(analysisDataRepository, never()).save(any());
    }

    /** 결과 없는 성공 콜백은 실패로 저장된다. 돌려주는 것도 그 실패여야 한다. */
    @Test
    void 결과가_없는_성공_콜백은_실패로_돌려준다() {
        when(analysisDataRepository.findById("job-2")).thenReturn(Optional.empty());

        CallbackSuccessResponse saved = service.saveResult(CallbackSuccessRequest.builder()
                .jobId("job-2")
                .status("succeeded")
                .build());

        assertThat(saved.getStatus()).isEqualTo("failed");
        assertThat(saved.getResult()).isNull();
    }

    /** 뒤늦은 실패 콜백은 무시된다. 흘려보낼 것이 있다면 그건 지켜낸 성공이다. */
    @Test
    void 이미_성공한_분석에_실패_콜백이_오면_성공을_돌려준다() {
        when(analysisDataRepository.findById("job-3")).thenReturn(Optional.of(AnalysisDataEntity.builder()
                .jobId("job-3")
                .status(AnalysisStatus.SUCCEEDED)
                .result(Map.of("project_summary", "요약"))
                .build()));

        CallbackSuccessResponse saved = service.saveResult(CallbackSuccessRequest.builder()
                .jobId("job-3")
                .status("failed")
                .error(CallbackSuccessRequest.Error.builder().status_code(500).message("늦은 실패").build())
                .build());

        assertThat(saved.getStatus()).isEqualTo("succeeded");
        assertThat(saved.getResult()).isEqualTo(Map.of("project_summary", "요약"));
        verify(analysisDataRepository, never()).save(any());
    }

    @Test
    void 실패_콜백은_이유까지_실어_돌려준다() {
        when(analysisDataRepository.findById("job-4")).thenReturn(Optional.empty());

        CallbackSuccessResponse saved = service.saveResult(CallbackSuccessRequest.builder()
                .jobId("job-4")
                .status("failed")
                .error(CallbackSuccessRequest.Error.builder().status_code(422).message("분석 불가").build())
                .build());

        assertThat(saved.getStatus()).isEqualTo("failed");
        assertThat(saved.getError().getStatus_code()).isEqualTo(422);
        assertThat(saved.getError().getMessage()).isEqualTo("분석 불가");
    }
}
