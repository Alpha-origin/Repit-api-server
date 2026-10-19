package repit.repit_api_server.domain.userdata.interview.dto.response;

import repit.repit_api_server.domain.userdata.persona.entity.PersonaEntity;
import repit.repit_api_server.domain.userdata.persona.entity.enums.InterviewTone;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Level;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Role;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Type;

/**
 * 면접 응답에 붙는 대표 면접관. 목록·상세 화면이 성향·난이도·면접관 이름을 여기서 읽는다.
 *
 * <p>1:1은 그 면접의 면접관, N:1은 진행 순서 맨 앞 면접관이다. 피드백 응답의 성향·난이도와
 * 같은 규칙이다. 이름은 저장된 값 그대로다 — 1:1 면접관 이름 끝의 유일성 접미사는 화면이 뗀다.
 */
public record InterviewPersonaSummary(
        Long id,
        String personaName,
        Role personaRole,
        Type type,
        Level level,
        InterviewTone tone,
        String imageUrl
) {

    /** 면접관이 없으면(지워졌거나 걸려 있지 않으면) null이다. */
    public static InterviewPersonaSummary of(PersonaEntity persona) {
        if (persona == null) {
            return null;
        }
        return new InterviewPersonaSummary(
                persona.getPersonaId(),
                persona.getPersonaName(),
                persona.getRole(),
                persona.getType(),
                persona.getLevel(),
                persona.getTone(),
                persona.getImageUrl()
        );
    }
}
