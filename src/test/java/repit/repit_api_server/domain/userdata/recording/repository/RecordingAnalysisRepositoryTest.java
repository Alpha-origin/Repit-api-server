package repit.repit_api_server.domain.userdata.recording.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import repit.repit_api_server.domain.userdata.recording.entity.RecordingAnalysisEntity;
import repit.repit_api_server.domain.userdata.recording.entity.enums.RecordingAnalysisStatus;

import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 한 면접을 한 곳만 보내게 하는 조건부 갱신이 실제 DB에서 그렇게 도는지 본다.
 *
 * <p>목으로는 확인되지 않는다. 가려야 할 것은 JPQL의 상태 조건이 실제로 걸리는지다.
 * 테스트 트랜잭션은 끝나면 롤백된다.
 */
@SpringBootTest
@Transactional
class RecordingAnalysisRepositoryTest {

    @Autowired
    private RecordingAnalysisRepository repository;

    @Test
    void 기다리는_건은_한_번만_차지된다() {
        RecordingAnalysisEntity saved = repository.save(waiting());

        assertThat(repository.claim(saved.getAnalysisId())).isEqualTo(1);
        assertThat(repository.claim(saved.getAnalysisId())).isZero();
        assertThat(repository.findById(saved.getAnalysisId()).orElseThrow().getStatus())
                .isEqualTo(RecordingAnalysisStatus.SENDING);
    }

    @Test
    void 활동_시각은_기다리는_건에만_찍힌다() {
        RecordingAnalysisEntity saved = repository.save(waiting());
        LocalDateTime later = LocalDateTime.now().plusMinutes(5).withNano(0);

        assertThat(repository.touchIfWaiting(saved.getInterviewId(), later)).isEqualTo(1);
        assertThat(repository.findById(saved.getAnalysisId()).orElseThrow().getLastActivityAt()).isEqualTo(later);

        repository.claim(saved.getAnalysisId());
        assertThat(repository.touchIfWaiting(saved.getInterviewId(), later.plusMinutes(1))).isZero();
    }

    @Test
    void 조용해진_기다리는_건만_스윕에_잡힌다() {
        RecordingAnalysisEntity quiet = repository.save(waiting(LocalDateTime.now().minusMinutes(10)));
        RecordingAnalysisEntity recent = repository.save(waiting(LocalDateTime.now()));

        assertThat(repository.findAllByStatusAndLastActivityAtBefore(
                RecordingAnalysisStatus.WAITING, LocalDateTime.now().minusMinutes(2)))
                .extracting(RecordingAnalysisEntity::getAnalysisId)
                .contains(quiet.getAnalysisId())
                .doesNotContain(recent.getAnalysisId());
    }

    private static RecordingAnalysisEntity waiting() {
        return waiting(LocalDateTime.now());
    }

    private static RecordingAnalysisEntity waiting(LocalDateTime lastActivityAt) {
        return RecordingAnalysisEntity.builder()
                // 면접마다 한 행이라 다른 테스트 데이터와 겹치지 않는 번호를 쓴다.
                .interviewId(ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE))
                .status(RecordingAnalysisStatus.WAITING)
                .lastActivityAt(lastActivityAt)
                .build();
    }
}
