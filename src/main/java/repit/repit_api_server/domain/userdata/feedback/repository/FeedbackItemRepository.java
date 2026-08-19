package repit.repit_api_server.domain.userdata.feedback.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackItemEntity;

import java.util.List;

public interface FeedbackItemRepository extends JpaRepository<FeedbackItemEntity, Long> {

    List<FeedbackItemEntity> findAllByFeedbackIdOrderBySortOrderAsc(Long feedbackId);

    void deleteAllByFeedbackId(Long feedbackId);
}
