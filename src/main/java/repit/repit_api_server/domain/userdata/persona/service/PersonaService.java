package repit.repit_api_server.domain.userdata.persona.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import repit.repit_api_server.domain.userdata.persona.dto.request.PersonaRequest;
import repit.repit_api_server.domain.userdata.persona.dto.response.PersonaResponse;
import repit.repit_api_server.domain.userdata.persona.entity.PersonaEntity;
import repit.repit_api_server.domain.userdata.persona.repository.PersonaRepository;

import java.util.List;

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
        PersonaEntity persona = personaRepository.findById(id).orElse(null);
        assert persona != null;
        return PersonaResponse.from(persona);
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
