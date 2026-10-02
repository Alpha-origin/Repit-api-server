package repit.repit_api_server.domain.userdata.question.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.question.entity.QuestionCycleEntity;
import repit.repit_api_server.domain.userdata.question.entity.enums.CycleStatus;

import java.time.LocalDateTime;
import java.util.Optional;

public interface QuestionCycleRepository extends JpaRepository<QuestionCycleEntity, Long> {

    Optional<QuestionCycleEntity> findByJobId(String jobId);

    /**
     * 세트를 꺼낼 사이클을 잠근다. 면접 두 개가 동시에 시작해도 같은 세트를 꺼내지 않고,
     * 사이클 콜백이 ACTIVE를 정하는 판단과도 순서가 선다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<QuestionCycleEntity> findFirstByProfileJobIdAndModeAndStatusOrderByCycleNoAsc(
            String profileJobId, InterviewMode mode, CycleStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from QuestionCycleEntity c where c.cycleId = :cycleId")
    Optional<QuestionCycleEntity> lockById(@Param("cycleId") Long cycleId);

    Optional<QuestionCycleEntity> findTopByProfileJobIdAndModeOrderByCycleNoDesc(String profileJobId, InterviewMode mode);

    Optional<QuestionCycleEntity> findByProfileJobIdAndModeAndCycleNo(String profileJobId, InterviewMode mode, Integer cycleNo);

    /** 자료가 바뀌었다. 이전 종합 데이터로 만든 사이클을 남은 세트와 대기본째 버린다. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update QuestionCycleEntity c
               set c.status = repit.repit_api_server.domain.userdata.question.entity.enums.CycleStatus.RETIRED
             where c.userId = :userId
               and c.profileJobId <> :profileJobId
               and c.status <> repit.repit_api_server.domain.userdata.question.entity.enums.CycleStatus.RETIRED
            """)
    int retireOthers(@Param("userId") Long userId, @Param("profileJobId") String profileJobId);

    /**
     * 접수 응답으로 받은 작업 id를 남긴다. 그 사이 콜백이 먼저 와 사이클이 끝났으면 건드리지 않는다.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update QuestionCycleEntity c set c.jobId = :jobId
             where c.cycleId = :cycleId
               and c.jobId is null
               and c.status = repit.repit_api_server.domain.userdata.question.entity.enums.CycleStatus.GENERATING
            """)
    int recordJob(@Param("cycleId") Long cycleId, @Param("jobId") String jobId);

    /** 요청을 보내지 못했다. 생성 중으로 남겨두면 아무도 다시 요청하지 않는다. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update QuestionCycleEntity c
               set c.status = repit.repit_api_server.domain.userdata.question.entity.enums.CycleStatus.FAILED,
                   c.errorMessage = :errorMessage,
                   c.completedAt = :now
             where c.cycleId = :cycleId
               and c.status = repit.repit_api_server.domain.userdata.question.entity.enums.CycleStatus.GENERATING
            """)
    int markSendFailed(@Param("cycleId") Long cycleId, @Param("errorMessage") String errorMessage,
                       @Param("now") LocalDateTime now);

    /**
     * 질문을 기다리는 면접이 있어 실패한 사이클을 한 번 더 요청할 권리를 차지한다.
     * 이미 다시 요청한 사이클이면 0이 돌아온다.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update QuestionCycleEntity c
               set c.status = repit.repit_api_server.domain.userdata.question.entity.enums.CycleStatus.GENERATING,
                   c.attempt = c.attempt + 1,
                   c.jobId = null,
                   c.errorMessage = null,
                   c.completedAt = null,
                   c.requestedAt = :now
             where c.cycleId = :cycleId
               and c.status = repit.repit_api_server.domain.userdata.question.entity.enums.CycleStatus.FAILED
               and c.attempt < :maxAttempts
            """)
    int claimRetry(@Param("cycleId") Long cycleId, @Param("maxAttempts") int maxAttempts,
                   @Param("now") LocalDateTime now);
}
