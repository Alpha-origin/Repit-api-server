package repit.repit_api_server.domain.userdata.persona.dto.request;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Gender;
import repit.repit_api_server.domain.userdata.persona.entity.enums.InterviewTone;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Level;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Major;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Role;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Type;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PersonaRequest {

    private String personaName;

    // 필수. TECH / HR / CEO.
    private Role role;

    // 읽지 않는다. 기술 면접관 전공은 로그인 사용자의 직무로 정한다(구버전 Web 호환용으로만 남김).
    private Major major;

    private Type type;

    // 필수. GENTLE / DIRECT / PRESSURING.
    private InterviewTone tone;

    // 필수. EASY / NORMAL / HARD.
    private Level level;

    private int career;

    private Gender gender;

    // 카드 사진·설명. 없으면 비워 보낸다.
    private String imageUrl;

    private String description;

    // 선택. 음성 카탈로그 번호 1~5. 비우면 채팅 서버가 기본 음성을 쓴다.
    private Integer voiceIndex;

}
