package repit.repit_api_server.domain.userdata.interview.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewCreateRequestEntity;

import java.util.Optional;
import java.util.UUID;

public interface InterviewCreateRequestRepository extends JpaRepository<InterviewCreateRequestEntity, Long> {

    Optional<InterviewCreateRequestEntity> findByUserIdAndIdempotencyKey(Long userId, UUID idempotencyKey);
}
