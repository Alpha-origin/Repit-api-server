package repit.repit_api_server.domain.userdata.analysis.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import repit.repit_api_server.domain.userdata.analysis.entity.AudioAnalysisResultEntity;

import java.util.List;

public interface AudioAnalysisResultRepository extends JpaRepository<AudioAnalysisResultEntity, Long> {

    List<AudioAnalysisResultEntity> findAllByAnalysisIdOrderByResultIdAsc(Long analysisId);

    void deleteAllByAnalysisId(Long analysisId);
}
