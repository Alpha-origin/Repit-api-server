package repit.repit_api_server.domain.company.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import repit.repit_api_server.domain.company.entity.CompanySourceEntity;

public interface CompanySourceRepository extends JpaRepository<CompanySourceEntity, Long> {

    // 한 문장으로 바로 지운다. 하나씩 지우면 Hibernate가 플러시에서 삽입을 삭제보다 먼저 내보내,
    // 같은 출처 키를 다시 넣을 때 (company_id, source_key) 유일 제약에 걸린다.
    @Modifying
    @Query("delete from CompanySourceEntity s where s.companyId = :companyId")
    void deleteAllByCompanyId(Long companyId);
}
