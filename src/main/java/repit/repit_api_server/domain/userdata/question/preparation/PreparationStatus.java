package repit.repit_api_server.domain.userdata.question.preparation;

/**
 * 면접에 들어갈 수 있는지를 한 값으로 말한다.
 *
 * <p>질문 재작성 상태와 채팅 서버 전달 여부를 클라이언트가 조합해 판단하던 것을 서버가 대신
 * 정한다. 두 축을 각자 읽으면 "재작성은 성공했는데 면접은 열리지 않은" 상태를 성공으로 읽는
 * 쪽과 실패로 읽는 쪽이 갈린다.
 */
public enum PreparationStatus {
    /** 면접 시작을 아직 요청하지 않았다. */
    NOT_REQUESTED,
    /** 질문을 만드는 중이거나 채팅 서버로 넘기는 중이다. */
    PREPARING,
    /** 채팅 서버에 면접이 열렸다. 이 값일 때만 입장할 수 있다. */
    READY,
    /** 면접을 열지 못했다. 어느 단계에서 멈췄는지는 failureStage가 가리킨다. */
    FAILED
}
