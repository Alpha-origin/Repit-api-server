package repit.repit_api_server.domain.userdata.feedback.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackEntity;

import java.util.List;
import java.util.Optional;

public interface FeedbackRepository extends JpaRepository<FeedbackEntity, Long> {

    Optional<FeedbackEntity> findByJobId(String jobId);

    Optional<FeedbackEntity> findTopByInterviewIdOrderByCreatedAtDesc(Long interviewId);

    Optional<FeedbackEntity> findTopBySessionIdOrderByCreatedAtDesc(String sessionId);

    // 사용자의 피드백 전체. 최근 것부터 본다.
    List<FeedbackEntity> findAllByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * 콜백이 접수 응답보다 먼저 와 만든 행에 이 작업이 보낸 문항 목록을 채운다. 이미 있으면 두지 않는다. 엔티티 저장으로는 이
     * 열을 바꾸지 않으므로(updatable = false) 콜백이 같은 행을 다시 저장해도 지워지지 않는다.
     */
    @Transactional
    @Modifying
    @Query(value = """
            update feedback
               set requested_question_ids = cast(:questionIds as jsonb)
             where feedback_id = :feedbackId
               and requested_question_ids is null
            """, nativeQuery = true)
    int recordRequestedQuestionIds(@Param("feedbackId") Long feedbackId, @Param("questionIds") String questionIdsJson);
}
