package repit.repit_api_server.domain.userdata.question.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import repit.repit_api_server.domain.metadata.dto.response.GenerateResponse;
import repit.repit_api_server.domain.metadata.entity.AnalysisDataEntity;
import repit.repit_api_server.domain.metadata.entity.enums.AnalysisResultType;
import repit.repit_api_server.domain.metadata.entity.enums.AnalysisStatus;
import repit.repit_api_server.domain.metadata.repository.AnalysisDataRepository;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.question.repository.QuestionCycleRepository;
import repit.repit_api_server.global.client.AiServerClient;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 같은 사이클을 두 요청이 동시에 처음 만들어도 한 번만 만들고 한 번만 요청한다. 잠글 행이 아직 없는 순간이라
 * 행 잠금으로는 막을 수 없어, 권고 잠금이 정말 둘을 줄 세우는지 DB에 대고 본다.
 *
 * <p>트랜잭션이 커밋돼야 다른 스레드가 보므로 테스트 트랜잭션을 쓰지 않고, 만든 행은 끝나고 지운다.
 */
@SpringBootTest
class QuestionPoolServiceConcurrencyTest {

    @Autowired
    private QuestionPoolService questionPoolService;
    @Autowired
    private AnalysisDataRepository analysisDataRepository;
    @Autowired
    private QuestionCycleRepository questionCycleRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private AiServerClient aiServerClient;

    private final Long userId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);
    private final String profileJobId = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        analysisDataRepository.saveAndFlush(AnalysisDataEntity.builder()
                .jobId(profileJobId)
                .userId(userId)
                .resultType(AnalysisResultType.PROFILE)
                .status(AnalysisStatus.SUCCEEDED)
                .result(Map.of("profile", Map.of(), "projectSummary", Map.of()))
                .completedAt(LocalDateTime.now())
                .build());
        when(aiServerClient.requestQuestionCycle(any()))
                .thenAnswer(invocation -> GenerateResponse.builder().jobId("cycle-" + UUID.randomUUID()).build());
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from question_cycle where user_id = ?", userId);
        jdbc.update("delete from analysis_data where user_id = ?", userId);
    }

    /** 종합 데이터 콜백이 재전송되는 순간처럼 두 요청이 같은 지점에 서 있다가 함께 출발한다. */
    @Test
    void 같은_사이클을_동시에_처음_만들어도_한_번만_만들고_한_번만_요청한다() throws Exception {
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> transactionTemplate.executeWithoutResult(status -> {
            questionCycleRepository.lockCycleCreation(profileJobId, InterviewMode.SOLO.name());
            holding.countDown();
            awaitQuietly(release);
        }));
        assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> questionPoolService.startCycles(profileJobId));
        CompletableFuture<Void> second = CompletableFuture.runAsync(() -> questionPoolService.startCycles(profileJobId));
        awaitLockWaiters(2);
        release.countDown();

        // 유니크 제약에 걸리면 여기서 예외로 드러난다.
        CompletableFuture.allOf(holder, first, second).get(30, TimeUnit.SECONDS);

        List<String> modes = jdbc.queryForList(
                "select mode from question_cycle where profile_job_id = ? and cycle_no = 1 order by mode",
                String.class, profileJobId);
        assertThat(modes).containsExactly(InterviewMode.MULTI.name(), InterviewMode.SOLO.name());
        verify(aiServerClient, times(2)).requestQuestionCycle(any());
    }

    @Test
    void 모드가_다르면_서로_기다리지_않는다() {
        transactionTemplate.executeWithoutResult(status -> {
            questionCycleRepository.lockCycleCreation(profileJobId, InterviewMode.SOLO.name());

            assertThat(otherTransactionCanLock(InterviewMode.SOLO)).isFalse();
            assertThat(otherTransactionCanLock(InterviewMode.MULTI)).isTrue();
        });
    }

    private boolean otherTransactionCanLock(InterviewMode mode) {
        return CompletableFuture.supplyAsync(() -> transactionTemplate.execute(status -> jdbc.queryForObject(
                "select pg_try_advisory_xact_lock(hashtext(?), hashtext(?))", Boolean.class, profileJobId, mode.name())))
                .join();
    }

    /** 두 요청이 모두 잠금 앞에서 기다릴 때까지. 잠금을 걸지 않으면 여기까지 오지 못한다. */
    private void awaitLockWaiters(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (jdbc.queryForObject(
                "select count(*) from pg_locks where locktype = 'advisory' and objsubid = 2 and not granted",
                Integer.class) < count) {
            if (System.nanoTime() > deadline) {
                fail("사이클을 만드는 두 요청이 잠금 앞에서 기다리지 않았습니다.");
            }
            Thread.sleep(20);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
