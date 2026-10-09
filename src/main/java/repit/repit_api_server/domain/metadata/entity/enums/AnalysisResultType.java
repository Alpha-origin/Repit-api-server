package repit.repit_api_server.domain.metadata.entity.enums;

/**
 * 분석 결과가 어느 계약으로 만들어졌는지.
 *
 * <p>옛 /generate 결과와 새 /profile 결과가 같은 테이블에 섞인다. 면접 질문은 PROFILE에서만 나온다.
 */
public enum AnalysisResultType {
    /** /generate 결과. result.interview[]에 원질문이 바로 들어 있다. */
    LEGACY_GENERATE,
    /** /profile 결과. result는 { profile, projectSummary }이고 질문은 사이클로 따로 만든다. */
    PROFILE
}
