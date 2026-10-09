package repit.repit_api_server.domain.metadata.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 종합 데이터를 처음 요청하는 일은 사용자 단위로 잠근다. 잠금이 정말 걸려 다른 트랜잭션이 기다리는지 DB에 대고 본다.
 *
 * <p>테스트 트랜잭션은 끝나면 롤백되고, 잠금도 그때 풀린다.
 */
@SpringBootTest
@Transactional
class AnalysisDataRepositoryLockTest {

    @Autowired
    private AnalysisDataRepository analysisDataRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private final Long userId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);

    private boolean otherTransactionCanLock(long key) {
        return CompletableFuture.supplyAsync(() -> transactionTemplate.execute(status ->
                jdbcTemplate.queryForObject("select pg_try_advisory_xact_lock(?)", Boolean.class, key))).join();
    }

    @Test
    void 잠근_사용자는_다른_트랜잭션이_잠그지_못하고_다른_사용자는_잠근다() {
        assertThat(analysisDataRepository.lockProfileLaunch(userId)).isEqualTo(1);

        assertThat(otherTransactionCanLock(userId)).isFalse();
        assertThat(otherTransactionCanLock(userId + 1)).isTrue();
    }
}
