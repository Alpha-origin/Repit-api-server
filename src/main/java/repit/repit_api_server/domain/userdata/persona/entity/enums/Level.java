package repit.repit_api_server.domain.userdata.persona.entity.enums;

/**
 * 면접 난이도. 말투(type)·직책(role)과는 독립된 축이다.
 *
 * <p>같은 직책이라도 얼마나 깊게 파고드는지가 달라진다 — 압박형 CEO가 쉬울 수도, 친화형 기술 면접관이
 * 어려울 수도 있어야 한다.
 */
public enum Level {
    EASY,
    NORMAL,
    HARD
}
