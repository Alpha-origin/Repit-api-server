package repit.repit_api_server.domain.userdata.persona.dto.request;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Gender;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Major;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Type;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PersonaRequest {

    private String personaName;

    private Major major;

    private Type type;

    private int career;

    private Gender gender;

}
