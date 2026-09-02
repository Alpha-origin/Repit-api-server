package repit.repit_api_server.domain.userdata.persona.entity.enums;

/**
 * 면접관의 어조. 성향({@link Type})과는 독립된 축이다.
 *
 * <p>성향이 무엇을 묻는지라면 어조는 얼마나 세게 묻는지다 — 꼼꼼한 면접관이 부드럽게 물을 수도,
 * 친화적인 면접관이 몰아붙일 수도 있어야 한다.
 *
 * <p>표기는 성향과 마찬가지로 영문 대문자 상수로 고정한다. 이 이름 그대로 분석 서버에 나간다.
 */
public enum InterviewTone {
    GENTLE,
    DIRECT,
    PRESSURING
}
