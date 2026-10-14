package repit.repit_api_server.domain.userdata.feedback.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackDispatchEntity;
import repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackDispatchKind;

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

    Optional<FeedbackDispatchEntity> findByInterviewIdAndKind(Long interviewId, FeedbackDispatchKind kind);

    /** 파일이 덜 모인 채 조용해졌거나 실패 뒤 다시 시도할 때가 된 건. */
    @Query("select d from FeedbackDispatchEntity d where d.status = repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackDispatchStatus.WAITING "
            + "and d.lastActivityAt < :quietBefore "
            + "and (d.nextAttemptAt is null or d.nextAttemptAt <= :now)")
    List<FeedbackDispatchEntity> findDue(LocalDateTime quietBefore, LocalDateTime now);

    /** 기다리는 중인 건에만 활동 시각을 새로 찍는다. 이미 요청했거나 요청하는 중이면 건드리지 않는다. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update FeedbackDispatchEntity d set d.lastActivityAt = :now "
            + "where d.interviewId = :interviewId and d.kind = :kind "
            + "and d.status = repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackDispatchStatus.WAITING")
    int touchIfWaiting(Long interviewId, FeedbackDispatchKind kind, LocalDateTime now);

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
     * 끝난(DONE) 건을 다시 기다리는 자리로 돌려놓는다.
     *
     * <p>접수까지 성공하면 이 행은 DONE으로 닫힌다. 그런데 음성 분석은 접수가 끝이 아니라 결과 콜백이
     * 와야 끝이고, 그 콜백이 유실되면 다시 확인해 줄 사람이 없다. 그때 이 갱신으로 되돌려, 스윕이 요청
     * 경로를 한 번 더 태우게 한다(그 안에서 작업을 조회하거나 같은 요청 id로 다시 보낸다).
     *
     * <p>시도 횟수는 그대로 둔다. 되돌릴 때마다 초기화하면 결과가 영영 오지 않는 면접을 끝없이 다시
     * 보내게 된다. 유지하면 기존 시도 한도가 그대로 상한이 되어, 넘는 순간 FAILED로 닫힌다.
     *
     * <p>FAILED는 되돌리지 않는다. 사람이 볼 실패로 이미 닫힌 건이다.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update FeedbackDispatchEntity d set d.status = repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackDispatchStatus.WAITING, "
            + "d.claimedAt = null, d.nextAttemptAt = null, d.lastActivityAt = :lastActivityAt, d.lastError = :reason "
            + "where d.interviewId = :interviewId and d.kind = :kind "
            + "and d.status = repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackDispatchStatus.DONE")
    int reopen(Long interviewId, FeedbackDispatchKind kind, LocalDateTime lastActivityAt, String reason);

    /**
     * 사용자의 다시 요청을 받아 끝난(DONE·FAILED) 건을 기다리는 자리로 돌려놓는다. 바뀐 행이 있을 때만 받은 것이다.
     *
     * <p>한도와 "지금 요청 중이 아닌지"를 같은 문장에서 확인한다. 연달아 누른 요청이 둘 다 받아들여지거나 한도를
     * 넘지 않는다.
     *
     * <p>{@link #reopen}과 달리 시도 횟수를 0으로 되돌리고 FAILED도 되돌린다. 자동 재시도를 다 쓰고 닫힌 건이라도
     * 사람이 다시 요청했으면 처음처럼 시도한다. 마지막 활동 시각은 그대로 둔다 — 대개 유예 시간이 지나 있어 다음
     * 스윕이 곧바로 집는다.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update FeedbackDispatchEntity d set d.status = repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackDispatchStatus.WAITING, "
            + "d.claimedAt = null, d.nextAttemptAt = null, d.attemptCount = 0, d.lastError = null, "
            + "d.userRetryCount = d.userRetryCount + 1 "
            + "where d.interviewId = :interviewId and d.kind = :kind "
            + "and d.status in (repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackDispatchStatus.DONE, "
            + "repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackDispatchStatus.FAILED) "
            + "and d.userRetryCount < :maxRetries")
    int reopenForUserRetry(Long interviewId, FeedbackDispatchKind kind, int maxRetries);

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
