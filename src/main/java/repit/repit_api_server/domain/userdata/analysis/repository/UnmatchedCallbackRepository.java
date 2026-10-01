package repit.repit_api_server.domain.userdata.analysis.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import repit.repit_api_server.domain.userdata.analysis.entity.UnmatchedCallbackEntity;

public interface UnmatchedCallbackRepository extends JpaRepository<UnmatchedCallbackEntity, Long> {
}
