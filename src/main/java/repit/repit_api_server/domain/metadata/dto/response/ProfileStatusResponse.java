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

    /** 저장소가 private이다(403). */
    public static final String REPO_NOT_PUBLIC = "REPO_NOT_PUBLIC";
    /** PDF가 열리지 않거나 저장소 주소 형식이 틀렸다(422). */
    public static final String INVALID_MATERIAL = "INVALID_MATERIAL";
    /** 코드에서 확인되는 근거가 모자라 질문을 만들 수 없다(422). */
    public static final String INSUFFICIENT_EVIDENCE = "INSUFFICIENT_EVIDENCE";
    /** 분석 서버 오류이거나 결과를 받지 못했다. */
    public static final String INTERNAL = "INTERNAL";

    private String jobId;
    // NONE / PENDING / COMPLETED / FAILED
    private String status;
    // 실패했을 때만. 403(private 저장소), 422(잘못된 PDF·URL, 근거 부족), 500(내부 오류)
    private Integer errorStatusCode;
    private String errorMessage;
    // 실패했을 때만. REPO_NOT_PUBLIC / INVALID_MATERIAL / INSUFFICIENT_EVIDENCE / INTERNAL
    private String failureReason;
    private LocalDateTime completedAt;
    // 완료됐을 때만. 개요와 기술 스택을 보여주는 데 쓴다.
    private Object projectSummary;

    public static ProfileStatusResponse none() {
        return ProfileStatusResponse.builder().status(NONE).build();
    }

    /**
     * 실패 사유. 분석 서버는 잘못된 자료와 근거 부족을 같은 422로 보내 메시지로 가른다.
     *
     * <p>웹은 사유마다 안내가 다르다 — 저장소 공개 설정, 자료 교체, 자료 보강, 다시 시도.
     */
    public static String reasonOf(Integer statusCode, String message) {
        if (statusCode == null) {
            return INTERNAL;
        }
        if (statusCode == 403) {
            return REPO_NOT_PUBLIC;
        }
        if (statusCode == 422) {
            // ponytail: 분석 서버 문구("근거가 부족합니다")에 기댄다. 문구가 바뀌면 잘못된 자료로 읽힌다. 코드를 나누면 그쪽으로 옮긴다.
            return message != null && message.contains("근거") ? INSUFFICIENT_EVIDENCE : INVALID_MATERIAL;
        }
        return INTERNAL;
    }
}
