package repit.repit_api_server.domain.userdata.persona.dto.response;

import lombok.*;
import repit.repit_api_server.domain.userdata.persona.entity.PersonaEntity;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Gender;
import repit.repit_api_server.domain.userdata.persona.entity.enums.InterviewTone;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Level;
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
    private InterviewTone tone;
    private Level level;
    private int career;
    private Gender gender;
    // 카드에 쓰는 사진과 한 줄 소개. 등록할 때 비워 보냈으면 비어 있다.
    private String imageUrl;
    private String description;


    public static PersonaResponse from(PersonaEntity persona) {
        return new PersonaResponse(
                persona.getPersonaId(),
                persona.getPersonaName(),
                persona.getRole(),
                persona.getMajor(),
                persona.getType(),
                persona.getTone(),
                persona.getLevel(),
                persona.getCareer(),
                persona.getGender(),
                persona.getImageUrl(),
                persona.getDescription()
        );
    }
}
