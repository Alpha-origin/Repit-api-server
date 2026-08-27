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
        // 직책과 난이도에는 기본값을 두지 않는다. 조용히 채워 넣으면 인사·CEO 면접관이
        // 기술 면접관으로 저장되고, 저장된 뒤에는 무엇이 요청값이고 무엇이 기본값인지 구분할 방법이 없다.
        if (request.getRole() == null) {
            throw BusinessException.unprocessable("면접관 직책(role)을 지정해주세요.");
        }
        if (request.getLevel() == null) {
            throw BusinessException.unprocessable("면접 난이도(level)를 지정해주세요.");
        }
        Role role = request.getRole();
        // 전공은 기술 면접관에게만 있는 값이다. DB CHECK와 같은 규칙을 여기서 먼저 걸러 메시지를 남긴다.
        if (role == Role.TECH && request.getMajor() == null) {
            throw BusinessException.unprocessable("기술 면접관에게는 전공(major)이 필요합니다.");
        }

        PersonaEntity persona = PersonaEntity.builder()
                .personaName(request.getPersonaName())
                .role(role)
                .type(request.getType())
                .level(request.getLevel())
                .major(role == Role.TECH ? request.getMajor() : null)
                .career(request.getCareer())
                .gender(request.getGender())
                .imageUrl(blankToNull(request.getImageUrl()))
                .description(blankToNull(request.getDescription()))
                .build();

        PersonaEntity saved =  personaRepository.save(persona);

        return PersonaResponse.from(saved);
    }

    public PersonaResponse getPersonaById(Long id) {
        PersonaEntity persona = personaRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("페르소나를 찾을 수 없습니다"));
        return PersonaResponse.from(persona);
    }

    public PersonaResponse getPersonaByName(String name) {
        PersonaEntity persona = personaRepository.findByPersonaName(name)
                .orElseThrow(() -> BusinessException.notFound("페르소나를 찾을 수 없습니다: " + name));
        return PersonaResponse.from(persona);
    }

    /**
     * 면접관 목록. 직책을 주면 그 직책만 내려준다.
     *
     * <p>N:1 사전설정 화면은 슬롯마다 다른 면접관 풀을 보여줘야 한다. 전부 내려주고 프론트에서
     * 거르게 하면 슬롯 규칙이 화면 쪽에 흩어지므로 여기서 거른다.
     */
    public List<PersonaResponse> getAllPersona(Role role) {
        List<PersonaEntity> personas = role == null
                ? personaRepository.findAll()
                : personaRepository.findAllByRole(role);

        return personas.stream()
                .map(PersonaResponse::from)
                .toList();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
