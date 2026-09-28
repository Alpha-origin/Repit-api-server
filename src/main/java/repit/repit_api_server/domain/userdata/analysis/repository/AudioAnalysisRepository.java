package repit.repit_api_server.domain.userdata.analysis.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import repit.repit_api_server.domain.userdata.analysis.entity.AudioAnalysisEntity;

import java.util.List;
import java.util.Optional;

public interface AudioAnalysisRepository extends JpaRepository<AudioAnalysisEntity, Long> {

    Optional<AudioAnalysisEntity> findByRequestId(String requestId);

    Optional<AudioAnalysisEntity> findByJobId(String jobId);

    List<AudioAnalysisEntity> findAllByInterviewIdOrderByAnalysisIdAsc(Long interviewId);
}
