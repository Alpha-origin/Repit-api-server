package repit.repit_api_server.domain.userdata.interview.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import repit.repit_api_server.domain.userdata.interview.dto.request.PersonaRequest;
import repit.repit_api_server.domain.userdata.interview.dto.response.PersonaResponse;
import repit.repit_api_server.domain.userdata.interview.entity.PersonaEntity;
import repit.repit_api_server.domain.userdata.interview.repository.PersonaRepository;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PersonaService {

    private final PersonaRepository personaRepository;

    public PersonaResponse createPersona(PersonaRequest request) {
        PersonaEntity persona = PersonaEntity.builder()
                .personaName(request.getPersonaName())
                .type(request.getType())
                .major(request.getMajor())
                .career(request.getCareer())
                .gender(request.getGender())
                .build();

        PersonaEntity saved =  personaRepository.save(persona);

        return PersonaResponse.from(saved);
    }

    public PersonaResponse getPersonaById(Long id) {
        Optional<PersonaEntity> persona = personaRepository.findById(id);
        return PersonaResponse.from(persona.get());
    }

    public PersonaResponse getPersonaByName(String name) {
        PersonaEntity persona = personaRepository.findByPersonaName(name).orElse(null);
        assert persona != null;
        return PersonaResponse.from(persona);
    }

    public List<PersonaResponse> getAllPersona() {
        return personaRepository.findAll()
                .stream()
                .map(PersonaResponse::from)
                .toList();
    }
}
