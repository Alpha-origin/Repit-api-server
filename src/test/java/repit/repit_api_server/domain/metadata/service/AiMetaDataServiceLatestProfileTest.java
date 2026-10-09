package repit.repit_api_server.domain.metadata.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import repit.repit_api_server.domain.metadata.dto.response.ProfileStatusResponse;
import repit.repit_api_server.domain.metadata.entity.AnalysisDataEntity;
import repit.repit_api_server.domain.metadata.entity.enums.AnalysisResultType;
import repit.repit_api_server.domain.metadata.entity.enums.AnalysisStatus;
import repit.repit_api_server.domain.metadata.repository.AnalysisDataRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 마이페이지 상태 카드와 면접 설정이 보는 최근 종합 데이터 상태.
 *
 * <p>분석 서버는 잘못된 자료와 근거 부족을 같은 422로 보낸다. 사유를 가르지 않으면 웹은 자료를 바꾸라고 할지
 * 보강하라고 할지 모른다. 콜백을 잃어버린 분석을 분석 중으로 두면 면접 시작이 끝내 막힌다.
 */
@ExtendWith(MockitoExtension.class)
class AiMetaDataServiceLatestProfileTest {

    private static final Long USER_ID = 7L;

    @Mock
    private AnalysisDataRepository analysisDataRepository;

    private AiMetaDataService service;

    @BeforeEach
    void setUp() {
        service = new AiMetaDataService(analysisDataRepository);
        ReflectionTestUtils.setField(service, "profilePendingTimeout", Duration.ofMinutes(15));
    }

    private ProfileStatusResponse latest(AnalysisDataEntity data) {
        when(analysisDataRepository.findTopByUserIdAndResultTypeOrderByCreatedAtDesc(USER_ID, AnalysisResultType.PROFILE))
                .thenReturn(Optional.of(data));
        return service.getLatestProfile(USER_ID);
    }

    private AnalysisDataEntity failed(int statusCode, String message) {
        return AnalysisDataEntity.builder()
                .jobId("profile-1")
                .status(AnalysisStatus.FAILED)
                .errorStatusCode(statusCode)
                .errorMessage(message)
                .build();
    }

    @Test
    void 실패_사유를_상태_코드와_메시지로_가른다() {
        assertThat(latest(failed(403, "github 저장소 상태를 public으로 변경해주세요.")).getFailureReason())
                .isEqualTo(ProfileStatusResponse.REPO_NOT_PUBLIC);
        assertThat(latest(failed(422, "유효한 PDF 파일이 아닙니다. 진행할 수 없습니다.")).getFailureReason())
                .isEqualTo(ProfileStatusResponse.INVALID_MATERIAL);
        assertThat(latest(failed(422, "GitHub 저장소 URL 형식이 올바르지 않습니다.")).getFailureReason())
                .isEqualTo(ProfileStatusResponse.INVALID_MATERIAL);
        assertThat(latest(failed(422, "코드에서 확인할 수 있는 근거가 부족합니다. 저장소나 포트폴리오를 보강해 주세요."))
                .getFailureReason()).isEqualTo(ProfileStatusResponse.INSUFFICIENT_EVIDENCE);
        assertThat(latest(failed(500, "종합 데이터 생성 결과가 형식을 충족하지 못했습니다.")).getFailureReason())
                .isEqualTo(ProfileStatusResponse.INTERNAL);
    }

    @Test
    void 분석_중이면_사유를_싣지_않는다() {
        ProfileStatusResponse response = latest(AnalysisDataEntity.builder()
                .jobId("profile-1")
                .status(AnalysisStatus.PENDING)
                .createdAt(LocalDateTime.now().minusMinutes(1))
                .build());

        assertThat(response.getStatus()).isEqualTo(ProfileStatusResponse.PENDING);
        assertThat(response.getFailureReason()).isNull();
    }

    /** 분석 서버는 콜백을 보내지 못하면 결과를 버린다. 분석 중으로 두면 웹은 면접 시작도 다시 분석도 막는다. */
    @Test
    void 콜백을_잃어버린_분석은_실패로_내보낸다() {
        ProfileStatusResponse response = latest(AnalysisDataEntity.builder()
                .jobId("profile-1")
                .status(AnalysisStatus.PENDING)
                .createdAt(LocalDateTime.now().minusMinutes(16))
                .build());

        assertThat(response.getStatus()).isEqualTo(ProfileStatusResponse.FAILED);
        assertThat(response.getFailureReason()).isEqualTo(ProfileStatusResponse.INTERNAL);
        assertThat(response.getErrorMessage()).contains("다시 분석해주세요");
    }
}
