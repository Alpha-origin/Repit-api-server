package repit.repit_api_server.domain.userdata.interview.dto.response;

import lombok.*;
import repit.repit_api_server.domain.userdata.interview.entity.PersonaEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Gender;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Major;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Type;

@Getter
@Builder
@AllArgsConstructor
public class PersonaResponse {

    private Long personaId;
    private String personaName;
    private Major major;
    private Type type;
    private int career;
    private Gender gender;


    public static PersonaResponse from(PersonaEntity persona) {
        return new PersonaResponse(
                persona.getPersonaId(),
                persona.getPersonaName(),
                persona.getMajor(),
                persona.getType(),
                persona.getCareer(),
                persona.getGender()
        );
    }
}
