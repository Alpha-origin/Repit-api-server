package repit.repit_api_server.domain.userdata.interview.dto.response;

import lombok.*;
import repit.repit_api_server.domain.userdata.interview.entity.Persona;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Gender;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Major;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Type;

@Getter
@Builder
@AllArgsConstructor
public class createPersonaResponse {

    private Long id;
    private String personaName;
    private Major major;
    private Type type;
    private int career;
    private Gender gender;


    public static createPersonaResponse from(Persona persona) {
        return new createPersonaResponse(
                persona.getId(),
                persona.getPersonaName(),
                persona.getMajor(),
                persona.getType(),
                persona.getCareer(),
                persona.getGender()
        );
    }
}
