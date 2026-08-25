package repit.repit_api_server.domain.metadata.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import repit.repit_api_server.domain.metadata.entity.AnalysisDataEntity;

import java.util.Optional;

public interface AnalysisDataRepository extends JpaRepository<AnalysisDataEntity, String> {

    // 분석이 끝난(result가 채워진) 가장 최근 작업
    Optional<AnalysisDataEntity> findTopByUserIdAndResultIsNotNullOrderByCreatedAtDesc(Long userId);

    // 소유자만 갱신한다. 엔티티를 통째로 저장하면 콜백이 먼저 채워둔 result를 덮어쓸 수 있다.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AnalysisDataEntity a set a.userId = :userId where a.jobId = :jobId")
    int updateUserId(@Param("jobId") String jobId, @Param("userId") Long userId);
}
