package repit.repit_api_server.domain.metadata.repository;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import repit.repit_api_server.domain.metadata.entity.AnalysisDataEntity;
import repit.repit_api_server.domain.metadata.entity.enums.AnalysisResultType;
import repit.repit_api_server.domain.metadata.entity.enums.AnalysisStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AnalysisDataRepository extends JpaRepository<AnalysisDataEntity, String> {

    /**
     * 소유자 확인에만 쓰는 값.
     *
     * <p>jobId까지 같이 고르는 것은 행이 없는 것과 소유자가 비어 있는 것을 확실히 가르기 위해서다.
     * 둘이 섞이면 모르는 작업이 403으로, 소유자가 붙지 않은 분석이 404로 나간다. 앞은 남의 작업이
     * 있는지 없는지를 알려주고, 뒤는 접수가 틀어진 것을 잘못된 jobId처럼 보이게 해 원인을 가린다.
     *
     * <p>userId 하나만 골라도 구분이 되는지는 Spring Data가 한 칼럼짜리 프로젝션을 어떻게 읽느냐에
     * 달려 있다. 스칼라로 받으면 값이 null인 행과 없는 행이 같은 빈 Optional이 되고, 투영 프록시로
     * 받으면 갈린다. 그 동작에 기대지 않으려고 칼럼을 하나 더 고른다 — 같은 행을 읽는 값이라
     * 비용이 늘지 않는다. 실제로 갈리는지는 AnalysisDataRepositoryFindOwnerTest가 DB에 대고 본다.
     */
    interface AnalysisOwner {
        String getJobId();

        Long getUserId();
    }

    /**
     * 소유자만 읽는다. 엔티티를 불러오면 result jsonb까지 따라온다.
     *
     * <p>그 값은 이 테이블에서 가장 무겁다 — 포트폴리오 요약과 면접 질문 전체가 들어 있어,
     * Postgres가 TOAST에서 꺼내 오고 Hibernate가 Map으로 풀어낸다. 소유자를 견주는 데는
     * Long 하나면 되는데 구독과 결과 조회가 매번 그 비용을 냈다.
     */
    @Query("select a.jobId as jobId, a.userId as userId from AnalysisDataEntity a where a.jobId = :jobId")
    Optional<AnalysisOwner> findOwner(@Param("jobId") String jobId);

    /** 분석이 끝난(result가 채워진) 작업 중 가장 나중에 요청한 것. 지금 면접 질문의 재료가 되는 결과다. */
    default Optional<AnalysisDataEntity> findLatestCompleted(Long userId, AnalysisResultType resultType) {
        return findLatestCompleted(userId, resultType, PageRequest.of(0, 1)).stream().findFirst();
    }

    /**
     * 최근을 가리는 기준은 완료 시각이 아니라 요청 시각이다.
     *
     * <p>사용자의 지금 자료는 마지막으로 올린 자료다. 완료 시각으로 줄을 세우면 먼저 올린 자료의
     * 분석이 늦게 끝나거나 그 콜백이 다시 오는 순간 옛 자료가 최신으로 올라서, 새 자료로 만든 질문
     * 풀이 폐기되고 옛 자료로 면접이 열린다.
     *
     * <p>같은 jobId로 분석을 다시 요청하면 행을 재사용한다. 그때 {@link #clearPreviousRun}이 접수
     * 시각을 이번 요청 시각으로 옮겨, 다시 요청한 분석도 그 시점의 요청으로 줄에 선다.
     */
    @Query("""
            select a from AnalysisDataEntity a
             where a.userId = :userId
               and a.resultType = :resultType
               and a.result is not null
             order by a.createdAt desc
            """)
    List<AnalysisDataEntity> findLatestCompleted(@Param("userId") Long userId,
                                                 @Param("resultType") AnalysisResultType resultType,
                                                 Pageable pageable);

    /** 이 종류의 분석을 한 번이라도 요청했는지. 예전 /generate 분석만 있는 사용자를 가려낸다. */
    boolean existsByUserIdAndResultType(Long userId, AnalysisResultType resultType);

    /**
     * 이 사용자를 트랜잭션이 끝날 때까지 잠근다. 종합 데이터를 처음 요청하는 일이 겹치지 않게 한다.
     *
     * <p>겹치면 같은 자료로 무거운 분석이 두 번 돈다. 잠글 행이 아직 없어 행 잠금 대신 권고 잠금을 쓰고,
     * 서버가 여러 대여도 막히도록 DB에 건다.
     */
    @Query(value = "select 1 from (select pg_advisory_xact_lock(:userId)) locked", nativeQuery = true)
    Integer lockProfileLaunch(@Param("userId") Long userId);

    /** 가장 최근에 요청한 작업. 끝났든 진행 중이든 지금 상태를 보여줄 때 쓴다. */
    Optional<AnalysisDataEntity> findTopByUserIdAndResultTypeOrderByCreatedAtDesc(Long userId,
                                                                               AnalysisResultType resultType);

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
     * <p>접수 시각도 이번 요청 시각으로 옮긴다. 최근 결과는 요청 순서로 가리므로, 옮기지 않으면
     * 다시 요청한 분석이 처음 요청한 자리에 머물러 그 사이 요청한 다른 분석에 밀린다.
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
                   a.completedAt = null,
                   a.createdAt = :requestedAt
             where a.jobId = :jobId
               and (a.completedAt is null or a.completedAt < :requestedAt)
            """)
    int clearPreviousRun(@Param("jobId") String jobId,
                         @Param("pending") AnalysisStatus pending,
                         @Param("requestedAt") LocalDateTime requestedAt);
}
