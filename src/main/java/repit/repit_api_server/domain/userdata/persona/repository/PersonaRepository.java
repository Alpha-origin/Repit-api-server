package repit.repit_api_server.domain.userdata.persona.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import repit.repit_api_server.domain.userdata.persona.entity.PersonaEntity;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Role;

import java.util.List;
import java.util.Optional;

public interface PersonaRepository extends JpaRepository<PersonaEntity, Long> {
    Optional<PersonaEntity> findByPersonaName(String personaName);

    List<PersonaEntity> findAllByRole(Role role);
}
