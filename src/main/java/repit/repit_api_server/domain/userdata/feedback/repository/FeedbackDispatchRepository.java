package repit.repit_api_server.domain.userdata.feedback.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackDispatchEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 상태는 조건부 갱신으로만 바꾼다.
 *
 * <p>영상 업로드, 기록 저장, 주기 스윕이 같은 면접을 동시에 볼 수 있다. 읽은 엔티티를 저장하는
 * 방식이면 둘 다 WAITING을 보고 채점을 두 번 요청하거나, 만료돼 다른 곳이 다시 차지한 건을
 * 늦게 끝난 쪽이 덮어쓴다. 그래서 바꿀 때마다 "지금도 그 상태인지"를 같은 문장에서 확인한다.
 */
public interface FeedbackDispatchRepository extends JpaRepository<FeedbackDispatchEntity, Long> {

    Optional<FeedbackDispatchEntity> findByInterviewId(Long interviewId);

    /** 영상이 덜 모인 채 조용해졌고, 다시 시도할 때가 된 건. */
    @Query("select d from FeedbackDispatchEntity d where d.status = repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackDispatchStatus.WAITING "
            + "and d.lastActivityAt < :quietBefore "
            + "and (d.nextAttemptAt is null or d.nextAttemptAt <= :now)")
    List<FeedbackDispatchEntity> findDue(LocalDateTime quietBefore, LocalDateTime now);

    /** 기다리는 중인 건에만 활동 시각을 새로 찍는다. 이미 요청했거나 요청하는 중이면 건드리지 않는다. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update FeedbackDispatchEntity d set d.lastActivityAt = :now "
            + "where d.interviewId = :interviewId and d.status = repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackDispatchStatus.WAITING")
    int touchIfWaiting(Long interviewId, LocalDateTime now);

    /**
     * WAITING -> SENDING. 바뀐 행이 있을 때만 차지한 것이다.
     *
     * <p>다시 시도할 때가 되지 않은 건은 차지하지 않는다. 영상이 올라와 바로 보내려 해도 앞선 실패의
     * 대기 시간은 지킨다. 차지한 시각은 뒤의 완료 기록이 "내가 차지한 그 건"인지 가리는 데도 쓴다.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update FeedbackDispatchEntity d set d.status = repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackDispatchStatus.SENDING, "
            + "d.claimedAt = :now, d.attemptCount = d.attemptCount + 1 "
            + "where d.dispatchId = :dispatchId and d.status = repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackDispatchStatus.WAITING "
            + "and (d.nextAttemptAt is null or d.nextAttemptAt <= :now)")
    int claim(Long dispatchId, LocalDateTime now);

    /**
     * 차지한 채 오래 끝나지 않은 건을 WAITING으로 되돌린다.
     *
     * <p>차지한 뒤 요청을 보내기 전에 재배포나 프로세스 종료가 일어나면 그 건은 SENDING에 남는다.
     * 스윕은 WAITING만 보므로 되돌리지 않으면 그 면접은 영영 채점되지 않는다.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update FeedbackDispatchEntity d set d.status = repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackDispatchStatus.WAITING, d.claimedAt = null, "
            + "d.nextAttemptAt = null, d.lastError = :reason "
            + "where d.status = repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackDispatchStatus.SENDING and d.claimedAt < :claimedBefore")
    int releaseExpiredClaims(LocalDateTime claimedBefore, String reason);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update FeedbackDispatchEntity d set d.status = repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackDispatchStatus.DONE, d.lastError = null "
            + "where d.dispatchId = :dispatchId and d.status = repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackDispatchStatus.SENDING and d.claimedAt = :claimedAt")
    int markDone(Long dispatchId, LocalDateTime claimedAt);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update FeedbackDispatchEntity d set d.status = repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackDispatchStatus.WAITING, d.claimedAt = null, "
            + "d.nextAttemptAt = :nextAttemptAt, d.lastError = :error "
            + "where d.dispatchId = :dispatchId and d.status = repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackDispatchStatus.SENDING and d.claimedAt = :claimedAt")
    int scheduleRetry(Long dispatchId, LocalDateTime claimedAt, LocalDateTime nextAttemptAt, String error);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update FeedbackDispatchEntity d set d.status = repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackDispatchStatus.FAILED, d.lastError = :error "
            + "where d.dispatchId = :dispatchId and d.status = repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackDispatchStatus.SENDING and d.claimedAt = :claimedAt")
    int markFailed(Long dispatchId, LocalDateTime claimedAt, String error);
}
