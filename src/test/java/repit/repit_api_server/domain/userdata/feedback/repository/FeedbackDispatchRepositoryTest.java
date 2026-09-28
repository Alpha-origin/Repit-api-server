package repit.repit_api_server.domain.userdata.feedback.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackDispatchEntity;
import repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackDispatchKind;
import repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackDispatchStatus;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 채점 요청의 차지·완료·만료가 실제 DB에서 조건대로 도는지 본다.
 *
 * <p>목으로는 확인되지 않는다. 가려야 할 것은 JPQL의 상태·시각 조건이 실제로 걸리는지다.
 * 테스트 트랜잭션은 끝나면 롤백된다.
 */
@SpringBootTest
@Transactional
class FeedbackDispatchRepositoryTest {

    @Autowired
    private FeedbackDispatchRepository repository;

    private static final LocalDateTime NOW = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);

    @Test
    void 기다리는_건은_한_번만_차지되고_시도_횟수와_시각이_남는다() {
        FeedbackDispatchEntity saved = repository.save(waiting(NOW));

        assertThat(repository.claim(saved.getDispatchId(), NOW)).isEqualTo(1);
        assertThat(repository.claim(saved.getDispatchId(), NOW)).isZero();

        FeedbackDispatchEntity claimed = repository.findById(saved.getDispatchId()).orElseThrow();
        assertThat(claimed.getStatus()).isEqualTo(FeedbackDispatchStatus.SENDING);
        assertThat(claimed.getAttemptCount()).isEqualTo(1);
        assertThat(claimed.getClaimedAt()).isEqualTo(NOW);
    }

    @Test
    void 다시_시도할_때가_되지_않은_건은_차지하지_않는다() {
        FeedbackDispatchEntity saved = repository.save(waiting(NOW));
        repository.claim(saved.getDispatchId(), NOW);
        repository.scheduleRetry(saved.getDispatchId(), NOW, NOW.plusMinutes(1), "AI 5xx");

        assertThat(repository.claim(saved.getDispatchId(), NOW.plusSeconds(30))).isZero();
        assertThat(repository.claim(saved.getDispatchId(), NOW.plusMinutes(1))).isEqualTo(1);
        assertThat(repository.findById(saved.getDispatchId()).orElseThrow().getAttemptCount()).isEqualTo(2);
    }

    @Test
    void 완료는_내가_차지한_그_건일_때만_기록된다() {
        FeedbackDispatchEntity saved = repository.save(waiting(NOW));
        repository.claim(saved.getDispatchId(), NOW);

        // 만료돼 다른 곳이 다시 차지했다면 차지 시각이 다르다. 늦게 끝난 쪽이 덮으면 안 된다.
        assertThat(repository.markDone(saved.getDispatchId(), NOW.minusSeconds(1))).isZero();
        assertThat(repository.markFailed(saved.getDispatchId(), NOW.minusSeconds(1), "x")).isZero();
        assertThat(repository.markDone(saved.getDispatchId(), NOW)).isEqualTo(1);
        assertThat(repository.findById(saved.getDispatchId()).orElseThrow().getStatus())
                .isEqualTo(FeedbackDispatchStatus.DONE);
    }

    @Test
    void 오래_끝나지_않은_차지만_기다리는_상태로_되돌린다() {
        FeedbackDispatchEntity stale = repository.save(waiting(NOW));
        FeedbackDispatchEntity fresh = repository.save(waiting(NOW));
        repository.claim(stale.getDispatchId(), NOW.minusMinutes(10));
        repository.claim(fresh.getDispatchId(), NOW);

        assertThat(repository.releaseExpiredClaims(NOW.minusMinutes(5), "끊김")).isGreaterThanOrEqualTo(1);

        FeedbackDispatchEntity released = repository.findById(stale.getDispatchId()).orElseThrow();
        assertThat(released.getStatus()).isEqualTo(FeedbackDispatchStatus.WAITING);
        assertThat(released.getClaimedAt()).isNull();
        assertThat(released.getLastError()).isEqualTo("끊김");
        assertThat(repository.findById(fresh.getDispatchId()).orElseThrow().getStatus())
                .isEqualTo(FeedbackDispatchStatus.SENDING);
    }

    @Test
    void 조용해졌고_다시_시도할_때가_된_기다리는_건만_스윕에_잡힌다() {
        FeedbackDispatchEntity quiet = repository.save(waiting(NOW.minusMinutes(10)));
        FeedbackDispatchEntity recent = repository.save(waiting(NOW));
        FeedbackDispatchEntity backingOff = repository.save(waiting(NOW.minusMinutes(10)));
        repository.claim(backingOff.getDispatchId(), NOW);
        repository.scheduleRetry(backingOff.getDispatchId(), NOW, NOW.plusMinutes(1), "AI 5xx");

        assertThat(repository.findDue(NOW.minusMinutes(2), NOW))
                .extracting(FeedbackDispatchEntity::getDispatchId)
                .contains(quiet.getDispatchId())
                .doesNotContain(recent.getDispatchId(), backingOff.getDispatchId());
    }

    @Test
    void 활동_시각은_기다리는_건에만_찍힌다() {
        FeedbackDispatchEntity saved = repository.save(waiting(NOW));
        LocalDateTime later = NOW.plusMinutes(5);

        assertThat(repository.touchIfWaiting(saved.getInterviewId(), FeedbackDispatchKind.FEEDBACK, later)).isEqualTo(1);
        assertThat(repository.findById(saved.getDispatchId()).orElseThrow().getLastActivityAt()).isEqualTo(later);

        repository.claim(saved.getDispatchId(), NOW);
        assertThat(repository.touchIfWaiting(saved.getInterviewId(), FeedbackDispatchKind.FEEDBACK, later.plusMinutes(1))).isZero();
    }

    /**
     * 끝난 건만 다시 기다리는 자리로 돌아간다.
     *
     * <p>음성 분석은 접수가 아니라 결과 콜백이 끝이라, 콜백이 유실되면 닫힌 건을 되돌려 다시 확인해야 한다.
     * 시도 횟수는 그대로 둬 한도가 계속 지켜진다. 사람이 볼 실패로 닫힌 FAILED는 되돌리지 않는다.
     */
    @Test
    void 끝난_건만_되돌리고_시도_횟수는_유지한다() {
        FeedbackDispatchEntity done = repository.save(waiting(NOW));
        repository.claim(done.getDispatchId(), NOW);
        repository.markDone(done.getDispatchId(), NOW);

        assertThat(repository.reopen(done.getInterviewId(), FeedbackDispatchKind.FEEDBACK, NOW, "결과 없음"))
                .isEqualTo(1);

        FeedbackDispatchEntity reopened = repository.findById(done.getDispatchId()).orElseThrow();
        assertThat(reopened.getStatus()).isEqualTo(FeedbackDispatchStatus.WAITING);
        assertThat(reopened.getClaimedAt()).isNull();
        assertThat(reopened.getNextAttemptAt()).isNull();
        assertThat(reopened.getLastActivityAt()).isEqualTo(NOW);
        assertThat(reopened.getAttemptCount()).isEqualTo(1);
        assertThat(reopened.getLastError()).isEqualTo("결과 없음");

        // 되돌린 건은 이미 기다리는 중이라 두 번 되돌지 않는다.
        assertThat(repository.reopen(done.getInterviewId(), FeedbackDispatchKind.FEEDBACK, NOW, "결과 없음")).isZero();
    }

    @Test
    void 사람이_볼_실패로_닫힌_건은_되돌리지_않는다() {
        FeedbackDispatchEntity failed = repository.save(waiting(NOW));
        repository.claim(failed.getDispatchId(), NOW);
        repository.markFailed(failed.getDispatchId(), NOW, "시도 한도");

        assertThat(repository.reopen(failed.getInterviewId(), FeedbackDispatchKind.FEEDBACK, NOW, "결과 없음")).isZero();
        assertThat(repository.findById(failed.getDispatchId()).orElseThrow().getStatus())
                .isEqualTo(FeedbackDispatchStatus.FAILED);
    }

    /** 한 면접에 채점과 음성 분석이 따로 한 행씩 있다. 한쪽을 건드려도 다른 쪽은 그대로다. */
    @Test
    void 같은_면접의_채점과_음성_분석은_따로_기다린다() {
        FeedbackDispatchEntity feedback = repository.save(waiting(NOW));
        FeedbackDispatchEntity audio = repository.save(waiting(feedback.getInterviewId(), FeedbackDispatchKind.AUDIO_ANALYSIS, NOW));
        LocalDateTime later = NOW.plusMinutes(5);

        assertThat(repository.touchIfWaiting(audio.getInterviewId(), FeedbackDispatchKind.AUDIO_ANALYSIS, later)).isEqualTo(1);

        assertThat(repository.findById(audio.getDispatchId()).orElseThrow().getLastActivityAt()).isEqualTo(later);
        assertThat(repository.findById(feedback.getDispatchId()).orElseThrow().getLastActivityAt()).isEqualTo(NOW);
        assertThat(repository.findByInterviewIdAndKind(audio.getInterviewId(), FeedbackDispatchKind.AUDIO_ANALYSIS))
                .get().extracting(FeedbackDispatchEntity::getDispatchId).isEqualTo(audio.getDispatchId());
    }

    private static FeedbackDispatchEntity waiting(LocalDateTime lastActivityAt) {
        // 면접마다 종류별로 한 행이라 다른 테스트 데이터와 겹치지 않는 번호를 쓴다.
        return waiting(ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE),
                FeedbackDispatchKind.FEEDBACK, lastActivityAt);
    }

    private static FeedbackDispatchEntity waiting(Long interviewId, FeedbackDispatchKind kind, LocalDateTime lastActivityAt) {
        return FeedbackDispatchEntity.builder()
                .interviewId(interviewId)
                .kind(kind)
                .status(FeedbackDispatchStatus.WAITING)
                .lastActivityAt(lastActivityAt)
                .build();
    }
}
