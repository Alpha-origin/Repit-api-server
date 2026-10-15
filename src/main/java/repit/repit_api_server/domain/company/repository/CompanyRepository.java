package repit.repit_api_server.domain.company.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import repit.repit_api_server.domain.company.entity.CompanyEntity;
import repit.repit_api_server.domain.company.entity.enums.CompanyStatus;

import java.util.List;
import java.util.Optional;

public interface CompanyRepository extends JpaRepository<CompanyEntity, Long> {

    Optional<CompanyEntity> findByCode(String code);

    List<CompanyEntity> findAllByStatus(CompanyStatus status);

    Optional<CompanyEntity> findByCompanyIdAndStatus(Long companyId, CompanyStatus status);
}
