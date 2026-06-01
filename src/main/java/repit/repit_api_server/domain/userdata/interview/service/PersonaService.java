package repit.repit_api_server.domain.userdata.interview.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import repit.repit_api_server.domain.userdata.interview.dto.request.savePersonaRequest;
import repit.repit_api_server.domain.userdata.interview.dto.response.savePersonaResponse;
import repit.repit_api_server.domain.userdata.interview.entity.Persona;
import repit.repit_api_server.domain.userdata.interview.repository.PersonaRepository;

@Service
@RequiredArgsConstructor
public class PersonaService {

    private final PersonaRepository personaRepository;

    public savePersonaResponse savePersona(savePersonaRequest request) {
        Persona persona = Persona.builder()
                .personaName(request.getPersonaName())
                .type(request.getType())
                .major(request.getMajor())
                .career(request.getCareer())
                .gender(request.getGender())
                .build();

        Persona saved =  personaRepository.save(persona);

        return savePersonaResponse.from(saved);
    }
}
