package repit.repit_api_server.domain.userdata.persona.dto.response;

import lombok.*;
import repit.repit_api_server.domain.userdata.persona.entity.PersonaEntity;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Gender;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Major;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Role;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Type;

@Getter
@Builder
@AllArgsConstructor
public class PersonaResponse {

    private Long personaId;
    private String personaName;
    private Role role;
    private Major major;
    private Type type;
    private int career;
    private Gender gender;


    public static PersonaResponse from(PersonaEntity persona) {
        return new PersonaResponse(
                persona.getPersonaId(),
                persona.getPersonaName(),
                persona.getRole(),
                persona.getMajor(),
                persona.getType(),
                persona.getCareer(),
                persona.getGender()
        );
    }
}
