package repit.repit_api_server.domain.metadata.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import repit.repit_api_server.domain.metadata.entity.AnalysisDataEntity;

public interface AnalysisDataRepository extends JpaRepository<AnalysisDataEntity, String> {
}
