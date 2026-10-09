package repit.repit_api_server.domain.userdata.question.repository;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import repit.repit_api_server.domain.metadata.entity.AnalysisDataEntity;
import repit.repit_api_server.domain.metadata.entity.enums.AnalysisResultType;
import repit.repit_api_server.domain.metadata.entity.enums.AnalysisStatus;
import repit.repit_api_server.domain.metadata.repository.AnalysisDataRepository;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.question.entity.QuestionCycleEntity;
import repit.repit_api_server.domain.userdata.question.entity.enums.CycleStatus;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 자료가 바뀌어 사이클을 버릴 때, 버리는 것은 먼저 요청한 종합 데이터의 사이클뿐이다.
 *
 * <p>옛 분석의 콜백이 자기가 지금 것인지 확인한 직후 새 분석이 끝나 사이클을 만들면, 옛 콜백이 뒤늦게 돌리는
 * 폐기가 새 사이클을 버릴 수 있다. 폐기 범위를 요청 순서로 묶어 두었는지 DB에 대고 본다.
 *
 * <p>테스트 트랜잭션은 끝나면 롤백된다.
 */
@SpringBootTest
@Transactional
class QuestionCycleRepositoryRetireTest {

    @Autowired
    private AnalysisDataRepository analysisDataRepository;
    @Autowired
    private QuestionCycleRepository questionCycleRepository;
    @Autowired
    private EntityManager entityManager;

    private final Long userId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);

    /** DB에 담긴 접수 시각으로 견주도록 다시 읽는다. 메모리의 값은 DB보다 정밀할 수 있다. */
    private AnalysisDataEntity profile() {
        String jobId = analysisDataRepository.saveAndFlush(AnalysisDataEntity.builder()
                .jobId(UUID.randomUUID().toString())
                .userId(userId)
                .resultType(AnalysisResultType.PROFILE)
                .status(AnalysisStatus.SUCCEEDED)
                .result(Map.of("profile", Map.of()))
                .completedAt(LocalDateTime.now())
                .build()).getJobId();
        entityManager.clear();
        return analysisDataRepository.findById(jobId).orElseThrow();
    }

    private Long cycle(AnalysisDataEntity profile) {
        return questionCycleRepository.saveAndFlush(QuestionCycleEntity.builder()
                .userId(userId)
                .profileJobId(profile.getJobId())
                .mode(InterviewMode.SOLO)
                .cycleNo(1)
                .status(CycleStatus.ACTIVE)
                .requestedAt(LocalDateTime.now())
                .build()).getCycleId();
    }

    private CycleStatus statusOf(Long cycleId) {
        return questionCycleRepository.findById(cycleId).orElseThrow().getStatus();
    }

    @Test
    void 나중에_요청한_종합_데이터는_먼저_요청한_것의_사이클만_버린다() {
        AnalysisDataEntity older = profile();
        AnalysisDataEntity newer = profile();
        Long olderCycle = cycle(older);
        Long newerCycle = cycle(newer);

        assertThat(questionCycleRepository.retireRequestedBefore(userId, newer.getCreatedAt())).isEqualTo(1);

        assertThat(statusOf(olderCycle)).isEqualTo(CycleStatus.RETIRED);
        assertThat(statusOf(newerCycle)).isEqualTo(CycleStatus.ACTIVE);
    }

    /** 옛 분석의 콜백이 늦게 와서 폐기가 새 분석보다 뒤에 돌아도 새 질문 풀은 남는다. */
    @Test
    void 먼저_요청한_종합_데이터는_나중_것의_사이클을_버리지_못한다() {
        AnalysisDataEntity older = profile();
        AnalysisDataEntity newer = profile();
        Long newerCycle = cycle(newer);

        assertThat(questionCycleRepository.retireRequestedBefore(userId, older.getCreatedAt())).isZero();

        assertThat(statusOf(newerCycle)).isEqualTo(CycleStatus.ACTIVE);
    }
}
