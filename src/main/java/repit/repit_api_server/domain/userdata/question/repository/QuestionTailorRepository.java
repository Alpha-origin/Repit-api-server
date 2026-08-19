package repit.repit_api_server.domain.userdata.question.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import repit.repit_api_server.domain.userdata.question.entity.QuestionTailorEntity;

import java.util.Optional;

public interface QuestionTailorRepository extends JpaRepository<QuestionTailorEntity, Long> {

    Optional<QuestionTailorEntity> findByJobId(String jobId);

    Optional<QuestionTailorEntity> findTopByInterviewIdOrderByCreatedAtDesc(Long interviewId);

    // 재작성에 성공한 것만. 폴백(tailored=false)은 원질문과 같아 병합할 이유가 없다.
    Optional<QuestionTailorEntity> findTopByAnalysisJobIdAndTailoredIsTrueOrderByCreatedAtDesc(String analysisJobId);

    Optional<QuestionTailorEntity> findTopByInterviewIdAndTailoredIsTrueOrderByCreatedAtDesc(Long interviewId);
}
