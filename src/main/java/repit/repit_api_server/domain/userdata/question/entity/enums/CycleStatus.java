package repit.repit_api_server.domain.userdata.question.entity.enums;

/** 질문 사이클의 상태. 뜻은 question_cycle.status 주석과 같다. */
public enum CycleStatus {
    /** 생성 요청 후 콜백 대기. */
    GENERATING,
    /** 생성 완료, 지금 쓰는 사이클이 끝나면 이어 쓸 대기본. */
    STANDBY,
    /** 지금 세트를 꺼내 쓰는 사이클. 종합 데이터·모드당 하나다. */
    ACTIVE,
    /** 세트 3개를 모두 썼다. */
    EXHAUSTED,
    /** 생성에 실패했다. 면접을 시작할 때 다시 요청한다. */
    FAILED,
    /** 자료가 바뀌어 폐기했다. 늦게 온 콜백도 버린다. */
    RETIRED
}
