package repit.repit_api_server.domain.company.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import repit.repit_api_server.domain.company.entity.CompanyFactEntity;
import repit.repit_api_server.domain.company.entity.enums.CompanyFactKind;

import java.util.List;

public interface CompanyFactRepository extends JpaRepository<CompanyFactEntity, Long> {

    List<CompanyFactEntity> findAllByCompanyIdOrderBySortOrder(Long companyId);

    // 목록 카드의 인재상 키워드. 기업마다 따로 읽지 않고 한 번에 읽어 기업별로 나눈다.
    List<CompanyFactEntity> findAllByKindOrderBySortOrder(CompanyFactKind kind);

    // 출처보다 먼저 지운다(fact가 출처를 가리킨다). 출처와 같은 이유로 한 문장으로 지운다.
    @Modifying
    @Query("delete from CompanyFactEntity f where f.companyId = :companyId")
    void deleteAllByCompanyId(Long companyId);
}
