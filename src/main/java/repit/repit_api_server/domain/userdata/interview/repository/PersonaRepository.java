package repit.repit_api_server.domain.userdata.interview.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import repit.repit_api_server.domain.userdata.interview.entity.PersonaEntity;

import java.util.Optional;

public interface PersonaRepository extends JpaRepository<PersonaEntity, Long> {
    Optional<PersonaEntity> findByPersonaName(String personaName);
}
