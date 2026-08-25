package repit.repit_api_server.domain.userdata.persona.entity.enums;

/**
 * 면접관의 직책. 말투(type)와는 독립된 축이다.
 *
 * <p>1:1 면접은 기술 면접관 한 명뿐이라 전부 {@link #TECH}다. N:1 면접은 세 직책이 한 명씩 들어간다.
 * 직책은 채점 관점에 반영된다 — 기술은 근거와 트레이드오프, 인사는 동기의 구체성, CEO는 우선순위 판단.
 */
public enum Role {
    TECH,
    HR,
    CEO
}
