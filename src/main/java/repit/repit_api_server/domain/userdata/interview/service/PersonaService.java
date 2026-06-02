package repit.repit_api_server.domain.userdata.interview.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import repit.repit_api_server.domain.userdata.interview.dto.request.PersonaRequest;
import repit.repit_api_server.domain.userdata.interview.dto.response.PersonaResponse;
import repit.repit_api_server.domain.userdata.interview.entity.Persona;
import repit.repit_api_server.domain.userdata.interview.repository.PersonaRepository;
import repit.repit_api_server.global.common.ApiResponse;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PersonaService {

    private final PersonaRepository personaRepository;

    public PersonaResponse createPersona(PersonaRequest request) {
        Persona persona = Persona.builder()
                .personaName(request.getPersonaName())
                .type(request.getType())
                .major(request.getMajor())
                .career(request.getCareer())
                .gender(request.getGender())
                .build();

        Persona saved =  personaRepository.save(persona);

        return PersonaResponse.from(saved);
    }

    public PersonaResponse getPersonaById(Long id) {
        Optional<Persona> persona = personaRepository.findById(id);
        return PersonaResponse.from(persona.get());
    }

    public PersonaResponse getPersonaByName(String name) {
        Optional<Persona> persona = personaRepository.findByPersonaName(name);
        return PersonaResponse.from(persona.get());
    }
}
