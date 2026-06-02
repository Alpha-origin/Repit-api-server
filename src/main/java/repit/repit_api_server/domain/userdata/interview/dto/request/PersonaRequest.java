package repit.repit_api_server.domain.userdata.interview.dto.request;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Gender;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Major;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Type;

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
