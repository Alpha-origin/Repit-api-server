package repit.repit_api_server.domain.metadata.entity.enums;

/**
 * 분석 결과가 어느 계약으로 만들어졌는지.
 *
 * <p>지금은 /profile 결과 하나뿐이다. 옛 /generate 결과(LEGACY_GENERATE)는 V31에서 지웠다.
 */
public enum AnalysisResultType {
    /** /profile 결과. result는 { profile, projectSummary }이고 질문은 사이클로 따로 만든다. */
    PROFILE
}
