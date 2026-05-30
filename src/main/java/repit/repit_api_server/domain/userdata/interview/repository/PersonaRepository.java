package repit.repit_api_server.domain.userdata.interview.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import repit.repit_api_server.domain.userdata.interview.entity.Persona;

public interface PersonaRepository extends JpaRepository<Persona, Long> {
}
