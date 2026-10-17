package repit.repit_api_server.domain.metadata.dto.response;

/**
 * {@code PUT /api/v1/metaData/materials} 200 응답(계약 C-1).
 *
 * <p>analysis가 null이면 분석 재료가 바뀌지 않아 분석을 다시 요청하지 않았다는 뜻이다.
 */
public record MaterialsSaveResponse(MaterialsResponse materials, Analysis analysis) {

    public static final String ANALYSIS_ACCEPTED = "accepted";
    /** 저장은 끝났지만 분석 요청이 실패했다. 자료는 그대로 두고 다시 분석하게 한다. */
    public static final String ANALYSIS_FAILED = "failed";
    /** 저장은 끝났지만 아직 분석하지 않는 직무라 요청하지 않았다(비개발, 분석 서버 새 요청 모양 C-3 전). */
    public static final String ANALYSIS_SKIPPED = "skipped";

    public record Analysis(String jobId, String status) {
    }
}
