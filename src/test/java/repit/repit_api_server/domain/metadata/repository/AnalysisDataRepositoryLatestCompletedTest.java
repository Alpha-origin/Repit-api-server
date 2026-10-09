package repit.repit_api_server.domain.metadata.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import repit.repit_api_server.domain.metadata.entity.AnalysisDataEntity;
import repit.repit_api_server.domain.metadata.entity.enums.AnalysisResultType;
import repit.repit_api_server.domain.metadata.entity.enums.AnalysisStatus;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 지금 종합 데이터는 끝난 것 중 가장 나중에 요청한 것이다. 완료 순서가 아니다.
 *
 * <p>완료 순서로 가리면 먼저 올린 자료의 분석이 늦게 끝나거나 그 콜백이 다시 오는 순간 옛 자료가 최신이 되어,
 * 새 자료로 만든 질문 풀이 버려진다. 접수 시각을 옮기는 갱신은 엔티티로는 할 수 없는 칼럼이라 DB에 대고 본다.
 *
 * <p>테스트 트랜잭션은 끝나면 롤백된다.
 */
@SpringBootTest
@Transactional
class AnalysisDataRepositoryLatestCompletedTest {

    @Autowired
    private AnalysisDataRepository analysisDataRepository;

    private final Long userId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);

    private String saveCompleted(LocalDateTime completedAt) {
        String jobId = UUID.randomUUID().toString();
        analysisDataRepository.saveAndFlush(AnalysisDataEntity.builder()
                .jobId(jobId)
                .userId(userId)
                .resultType(AnalysisResultType.PROFILE)
                .status(AnalysisStatus.SUCCEEDED)
                .result(Map.of("profile", Map.of()))
                .completedAt(completedAt)
                .build());
        return jobId;
    }

    @Test
    void 먼저_요청한_분석이_나중에_끝나도_나중에_요청한_분석이_지금_것이다() {
        LocalDateTime now = LocalDateTime.now();
        saveCompleted(now.plusMinutes(5));
        String later = saveCompleted(now);

        assertThat(analysisDataRepository.findLatestCompleted(userId, AnalysisResultType.PROFILE))
                .get().extracting(AnalysisDataEntity::getJobId).isEqualTo(later);
    }

    /** 같은 jobId로 다시 요청한 분석은 다시 요청한 시점의 요청으로 줄에 선다. */
    @Test
    void 같은_작업을_다시_요청하면_그_시점의_요청이_된다() {
        LocalDateTime now = LocalDateTime.now();
        String reused = saveCompleted(now);
        String other = saveCompleted(now);

        // DB가 마이크로초까지 담으므로 그 단위로 맞춘다.
        LocalDateTime requestedAt = now.plusMinutes(1).truncatedTo(ChronoUnit.MICROS);
        assertThat(analysisDataRepository.clearPreviousRun(reused, AnalysisStatus.PENDING, requestedAt)).isEqualTo(1);
        AnalysisDataEntity rerun = analysisDataRepository.findById(reused).orElseThrow();
        rerun.setStatus(AnalysisStatus.SUCCEEDED);
        rerun.setResult(Map.of("profile", Map.of()));
        rerun.setCompletedAt(requestedAt.plusMinutes(1));
        analysisDataRepository.saveAndFlush(rerun);

        assertThat(analysisDataRepository.findById(reused).orElseThrow().getCreatedAt()).isEqualTo(requestedAt);
        assertThat(analysisDataRepository.findLatestCompleted(userId, AnalysisResultType.PROFILE))
                .get().extracting(AnalysisDataEntity::getJobId).isEqualTo(reused)
                .isNotEqualTo(other);
    }
}
