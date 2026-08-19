package repit.repit_api_server.domain.userdata.feedback.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackEntity;

import java.util.Optional;

public interface FeedbackRepository extends JpaRepository<FeedbackEntity, Long> {

    Optional<FeedbackEntity> findByJobId(String jobId);

    Optional<FeedbackEntity> findTopByInterviewIdOrderByCreatedAtDesc(Long interviewId);

    Optional<FeedbackEntity> findTopBySessionIdOrderByCreatedAtDesc(String sessionId);
}
