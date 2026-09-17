package repit.repit_api_server.domain.userdata.feedback.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackDispatchEntity;
import repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackDispatchStatus;

import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 한 면접의 채점을 한 곳만 요청하게 하는 조건부 갱신이 실제 DB에서 그렇게 도는지 본다.
 *
 * <p>목으로는 확인되지 않는다. 가려야 할 것은 JPQL의 상태 조건이 실제로 걸리는지다.
 * 테스트 트랜잭션은 끝나면 롤백된다.
 */
@SpringBootTest
@Transactional
class FeedbackDispatchRepositoryTest {

    @Autowired
    private FeedbackDispatchRepository repository;

    @Test
    void 기다리는_건은_한_번만_차지된다() {
        FeedbackDispatchEntity saved = repository.save(waiting());

        assertThat(repository.claim(saved.getDispatchId())).isEqualTo(1);
        assertThat(repository.claim(saved.getDispatchId())).isZero();
        assertThat(repository.findById(saved.getDispatchId()).orElseThrow().getStatus())
                .isEqualTo(FeedbackDispatchStatus.SENDING);
    }

    @Test
    void 활동_시각은_기다리는_건에만_찍힌다() {
        FeedbackDispatchEntity saved = repository.save(waiting());
        LocalDateTime later = LocalDateTime.now().plusMinutes(5).withNano(0);

        assertThat(repository.touchIfWaiting(saved.getInterviewId(), later)).isEqualTo(1);
        assertThat(repository.findById(saved.getDispatchId()).orElseThrow().getLastActivityAt()).isEqualTo(later);

        repository.claim(saved.getDispatchId());
        assertThat(repository.touchIfWaiting(saved.getInterviewId(), later.plusMinutes(1))).isZero();
    }

    @Test
    void 조용해진_기다리는_건만_스윕에_잡힌다() {
        FeedbackDispatchEntity quiet = repository.save(waiting(LocalDateTime.now().minusMinutes(10)));
        FeedbackDispatchEntity recent = repository.save(waiting(LocalDateTime.now()));

        assertThat(repository.findAllByStatusAndLastActivityAtBefore(
                FeedbackDispatchStatus.WAITING, LocalDateTime.now().minusMinutes(2)))
                .extracting(FeedbackDispatchEntity::getDispatchId)
                .contains(quiet.getDispatchId())
                .doesNotContain(recent.getDispatchId());
    }

    private static FeedbackDispatchEntity waiting() {
        return waiting(LocalDateTime.now());
    }

    private static FeedbackDispatchEntity waiting(LocalDateTime lastActivityAt) {
        return FeedbackDispatchEntity.builder()
                // 면접마다 한 행이라 다른 테스트 데이터와 겹치지 않는 번호를 쓴다.
                .interviewId(ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE))
                .status(FeedbackDispatchStatus.WAITING)
                .lastActivityAt(lastActivityAt)
                .build();
    }
}
