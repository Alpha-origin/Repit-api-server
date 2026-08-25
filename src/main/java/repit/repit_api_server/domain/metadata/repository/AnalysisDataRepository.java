package repit.repit_api_server.domain.metadata.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import repit.repit_api_server.domain.metadata.entity.AnalysisDataEntity;
import repit.repit_api_server.domain.metadata.entity.enums.AnalysisStatus;

import java.time.LocalDateTime;
import java.util.Optional;

public interface AnalysisDataRepository extends JpaRepository<AnalysisDataEntity, String> {

    // 분석이 끝난(result가 채워진) 가장 최근 작업
    Optional<AnalysisDataEntity> findTopByUserIdAndResultIsNotNullOrderByCreatedAtDesc(Long userId);

    // 소유자만 갱신한다. 엔티티를 통째로 저장하면 콜백이 먼저 채워둔 result를 덮어쓸 수 있다.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AnalysisDataEntity a set a.userId = :userId where a.jobId = :jobId")
    int updateUserId(@Param("jobId") String jobId, @Param("userId") Long userId);

    /**
     * 같은 jobId로 분석을 다시 요청했을 때 지난 실행에 남은 결과를 걷어낸다.
     *
     * <p>걷어내지 않으면 구독이 붙는 순간 옛 결과가 완료 이벤트로 나간다. 분석 서버는 아직
     * 콜백을 보내지도 않은 시점이라, 클라이언트는 새 분석이 끝난 줄 알고 옛 결과를 집어 든다.
     *
     * <p>이번 요청보다 나중에 끝난 결과는 이번 실행의 콜백이다. 요청 접수보다 콜백이 먼저
     * 도착할 수 있어서, 그 결과까지 지우지 않도록 completedAt으로 조건을 건다.
     * 엔티티를 읽어 되저장하는 대신 조건을 담은 갱신 한 번으로 끝내, 읽고 쓰는 사이에 도착한
     * 콜백을 덮어쓰지 않는다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AnalysisDataEntity a
               set a.status = :pending,
                   a.result = null,
                   a.errorStatusCode = null,
                   a.errorMessage = null,
                   a.completedAt = null
             where a.jobId = :jobId
               and (a.completedAt is null or a.completedAt < :requestedAt)
            """)
    int clearPreviousRun(@Param("jobId") String jobId,
                         @Param("pending") AnalysisStatus pending,
                         @Param("requestedAt") LocalDateTime requestedAt);
}
