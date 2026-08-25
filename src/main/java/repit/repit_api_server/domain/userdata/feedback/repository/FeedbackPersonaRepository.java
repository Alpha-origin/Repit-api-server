package repit.repit_api_server.domain.userdata.feedback.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackPersonaEntity;

import java.util.List;

public interface FeedbackPersonaRepository extends JpaRepository<FeedbackPersonaEntity, Long> {

    List<FeedbackPersonaEntity> findAllByFeedbackIdOrderBySortOrderAsc(Long feedbackId);

    void deleteAllByFeedbackId(Long feedbackId);
}
