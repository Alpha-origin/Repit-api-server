package repit.repit_api_server.domain.metadata.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 마이페이지와 면접 설정이 보는 최근 종합 데이터 분석 상태. */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProfileStatusResponse {
    public static final String NONE = "NONE";
    public static final String PENDING = "PENDING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";

    private String jobId;
    // NONE / PENDING / COMPLETED / FAILED
    private String status;
    // 실패했을 때만. 403(private 저장소), 422(잘못된 PDF·URL), 500(내부 오류)
    private Integer errorStatusCode;
    private String errorMessage;
    private LocalDateTime completedAt;
    // 완료됐을 때만. 개요와 기술 스택을 보여주는 데 쓴다.
    private Object projectSummary;

    public static ProfileStatusResponse none() {
        return ProfileStatusResponse.builder().status(NONE).build();
    }
}
