package repit.repit_api_server.domain.userdata.interview.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;

import java.util.List;
import java.util.Optional;

public interface InterviewRepository extends JpaRepository<InterviewEntity, Long> {
    List<InterviewEntity> findAllByUserId(Long userId);

    Optional<InterviewEntity> findBySessionId(String sessionId);
}
