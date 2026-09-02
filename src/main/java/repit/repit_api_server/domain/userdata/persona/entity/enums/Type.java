package repit.repit_api_server.domain.userdata.persona.entity.enums;

/**
 * 면접관의 성향. 직책(role)·난이도(level)·어조({@link InterviewTone})와는 독립된 축이다.
 *
 * <p>무엇을 파고드는지를 정한다 — 친화형은 지원자의 경험과 동기를, 현실형은 실제 업무에서
 * 통하는지를, 꼼꼼형은 근거와 빈틈을 본다. 얼마나 세게 말하는지는 어조가 따로 정한다.
 *
 * <p>표기는 영문 대문자 상수로 고정한다. 이 이름 그대로 분석 서버에 성향 키로 나가며,
 * 모르는 값이 와도 기본 지침으로 폴백하고 422로 깨지지는 않는다.
 */
public enum Type {
    FRIENDLY,
    REALISTIC,
    METICULOUS
}
