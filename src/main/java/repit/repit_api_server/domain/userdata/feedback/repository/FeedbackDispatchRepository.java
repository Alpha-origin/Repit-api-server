package repit.repit_api_server.domain.userdata.feedback.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackDispatchEntity;
import repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackDispatchStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface FeedbackDispatchRepository extends JpaRepository<FeedbackDispatchEntity, Long> {

    Optional<FeedbackDispatchEntity> findByInterviewId(Long interviewId);

    List<FeedbackDispatchEntity> findAllByStatusAndLastActivityAtBefore(FeedbackDispatchStatus status,
                                                                       LocalDateTime before);

    /**
     * 기다리는 중인 건에만 활동 시각을 새로 찍는다. 이미 요청했거나 요청하는 중이면 건드리지 않는다.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update FeedbackDispatchEntity d set d.lastActivityAt = :now "
            + "where d.interviewId = :interviewId and d.status = repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackDispatchStatus.WAITING")
    int touchIfWaiting(Long interviewId, LocalDateTime now);

    /**
     * WAITING -> SENDING. 바뀐 행이 있을 때만 차지한 것이다.
     *
     * <p>영상 업로드, 기록 저장, 주기 스윕이 같은 면접을 동시에 볼 수 있다. 읽고 나서 상태를
     * 바꾸면 둘 다 WAITING을 보고 피드백을 두 번 요청한다. 조건부 갱신 한 문장으로 한 곳만 통과시킨다.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update FeedbackDispatchEntity d set d.status = repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackDispatchStatus.SENDING "
            + "where d.dispatchId = :dispatchId and d.status = repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackDispatchStatus.WAITING")
    int claim(Long dispatchId);
}
