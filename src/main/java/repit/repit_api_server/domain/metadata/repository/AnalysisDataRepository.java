package repit.repit_api_server.domain.metadata.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import repit.repit_api_server.domain.metadata.entity.AnalysisDataEntity;

import java.util.Optional;

public interface AnalysisDataRepository extends JpaRepository<AnalysisDataEntity, String> {

    // 분석이 끝난(result가 채워진) 가장 최근 작업
    Optional<AnalysisDataEntity> findTopByUserIdAndResultIsNotNullOrderByCreatedAtDesc(Long userId);
}
