package repit.repit_api_server.domain.userdata.persona.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import repit.repit_api_server.domain.userdata.persona.dto.request.PersonaRequest;
import repit.repit_api_server.domain.userdata.persona.dto.response.PersonaResponse;
import repit.repit_api_server.domain.userdata.persona.entity.PersonaEntity;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Role;
import repit.repit_api_server.domain.userdata.persona.repository.PersonaRepository;
import repit.repit_api_server.global.exception.BusinessException;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PersonaService {

    private final PersonaRepository personaRepository;

    public PersonaResponse createPersona(PersonaRequest request) {
        // 직책을 안 보내던 기존 요청은 전부 기술 면접관이었다.
        Role role = request.getRole() == null ? Role.TECH : request.getRole();
        // 전공은 기술 면접관에게만 있는 값이다. DB CHECK와 같은 규칙을 여기서 먼저 걸러 메시지를 남긴다.
        if (role == Role.TECH && request.getMajor() == null) {
            throw BusinessException.unprocessable("기술 면접관에게는 전공(major)이 필요합니다.");
        }

        PersonaEntity persona = PersonaEntity.builder()
                .personaName(request.getPersonaName())
                .role(role)
                .type(request.getType())
                .major(role == Role.TECH ? request.getMajor() : null)
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
