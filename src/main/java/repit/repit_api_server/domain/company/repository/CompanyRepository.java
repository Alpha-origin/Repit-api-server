package repit.repit_api_server.domain.company.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import repit.repit_api_server.domain.company.entity.CompanyEntity;
import repit.repit_api_server.domain.company.entity.enums.CompanyStatus;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CompanyRepository extends JpaRepository<CompanyEntity, Long> {

    Optional<CompanyEntity> findByCode(String code);

    List<CompanyEntity> findAllByCodeIn(Collection<String> codes);

    List<CompanyEntity> findAllByStatus(CompanyStatus status);

    // 목록에 모회사 이름을 함께 싣는다. 모회사를 기업마다 따로 읽지 않도록 한 번에 가져온다.
    @EntityGraph(attributePaths = "parent")
    List<CompanyEntity> findWithParentByStatus(CompanyStatus status);

    /**
     * 스냅샷을 만들 때 기업 행을 공유 잠금(FOR SHARE)으로 읽는다. 가져오기는 같은 행을 FOR UPDATE로 잡고 기업·fact를
     * 바꾸므로, 둘이 겹치면 한쪽이 커밋할 때까지 기다린다 — 이전 요약과 새 fact가 섞인 스냅샷이 생기지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select c from CompanyEntity c where c.companyId = :companyId")
    Optional<CompanyEntity> findForShareByCompanyId(@Param("companyId") Long companyId);

    // 가져오기가 기존 기업을 고치기 전에 잡는다. 위 공유 잠금과 짝이다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CompanyEntity c where c.code = :code")
    Optional<CompanyEntity> findForUpdateByCode(@Param("code") String code);

    // 숨긴 기업이 들고 있는 식별자를 놓게 한다. 같은 법인을 다른 code로 다시 낸 패키지가 UNIQUE에 걸리지 않게 한다.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update CompanyEntity c
               set c.dartCorpCode = null
             where c.status = repit.repit_api_server.domain.company.entity.enums.CompanyStatus.HIDDEN
               and c.dartCorpCode in :dartCorpCodes
            """)
    int releaseHiddenDartCorpCodes(@Param("dartCorpCodes") Collection<String> dartCorpCodes);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update CompanyEntity c
               set c.businessRegNo = null
             where c.status = repit.repit_api_server.domain.company.entity.enums.CompanyStatus.HIDDEN
               and c.businessRegNo in :businessRegNos
            """)
    int releaseHiddenBusinessRegNos(@Param("businessRegNos") Collection<String> businessRegNos);
}
