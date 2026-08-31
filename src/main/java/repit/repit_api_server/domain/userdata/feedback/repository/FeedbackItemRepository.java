package repit.repit_api_server.domain.userdata.feedback.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackItemEntity;

import java.util.Collection;
import java.util.List;

public interface FeedbackItemRepository extends JpaRepository<FeedbackItemEntity, Long> {

    List<FeedbackItemEntity> findAllByFeedbackIdOrderBySortOrderAsc(Long feedbackId);

    // 여러 피드백을 한 번에 볼 때 쓴다. 피드백마다 따로 조회하면 건수만큼 쿼리가 늘어난다.
    List<FeedbackItemEntity> findAllByFeedbackIdInOrderByFeedbackIdAscSortOrderAsc(Collection<Long> feedbackIds);

    void deleteAllByFeedbackId(Long feedbackId);
}
