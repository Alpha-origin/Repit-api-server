package repit.repit_api_server.domain.userdata.persona.dto.request;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Gender;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Major;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Role;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Type;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PersonaRequest {

    private String personaName;

    // 비우면 기술 면접관(TECH)으로 만든다. 1:1 면접용 페르소나는 전부 여기에 해당한다.
    private Role role;

    // 기술 면접관에게만 필요하다. 인사팀·CEO는 비워 보낸다.
    private Major major;

    private Type type;

    private int career;

    private Gender gender;

}
