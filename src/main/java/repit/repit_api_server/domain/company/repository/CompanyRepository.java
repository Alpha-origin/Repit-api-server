package repit.repit_api_server.domain.company.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
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

    List<CompanyEntity> findAllByStatus(CompanyStatus status);

    // 목록에 모회사 이름을 함께 싣는다. 모회사를 기업마다 따로 읽지 않도록 한 번에 가져온다.
    @EntityGraph(attributePaths = "parent")
    List<CompanyEntity> findWithParentByStatus(CompanyStatus status);

    Optional<CompanyEntity> findByCompanyIdAndStatus(Long companyId, CompanyStatus status);

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
