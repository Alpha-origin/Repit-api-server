package repit.repit_api_server.domain.userdata.question.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import repit.repit_api_server.domain.userdata.question.entity.QuestionTailorEntity;

import java.util.Optional;

public interface QuestionTailorRepository extends JpaRepository<QuestionTailorEntity, Long> {

    Optional<QuestionTailorEntity> findByJobId(String jobId);

    Optional<QuestionTailorEntity> findTopByInterviewIdOrderByCreatedAtDesc(Long interviewId);
}
