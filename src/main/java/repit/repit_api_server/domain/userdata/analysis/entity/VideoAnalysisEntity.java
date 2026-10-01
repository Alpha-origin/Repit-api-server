package repit.repit_api_server.domain.userdata.analysis.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import repit.repit_api_server.domain.userdata.analysis.entity.enums.VideoAnalysisRequestedBy;
import repit.repit_api_server.domain.userdata.analysis.entity.enums.VideoAnalysisStatus;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 분석 서버에 맡긴 영상 분석 요청 하나.
 *
 * <p>바뀐 컬럼만 쓴다({@link DynamicUpdate}). 콜백이 결과를 저장하는 동안 스윕이 보낸 횟수를 올리거나 작업 id를
 * 적을 수 있는데, 읽은 엔티티 전체를 쓰면 한쪽이 다른 쪽의 값을 옛 값으로 되돌린다.
 */
@Entity
@Table(name = "video_analysis")
@DynamicUpdate
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class VideoAnalysisEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "analysis_id")
    private Long analysisId;

    @Column(nullable = false)
    private Long interviewId;

    @Column(nullable = false)
    private Long userId;

    // 면접에 채팅 세션이 없으면 비어 있고, 그 행은 보내지 않고 닫힌다.
    @Column(length = 64)
    private String sessionId;

    // 분석한 영상. 분석 서버에는 videoId로 넘긴다.
    @Column(nullable = false)
    private Long recordingId;

    @Column(nullable = false, length = 64, unique = true)
    private String requestId;

    @Column(unique = true)
    private String jobId;

    // 같은 묶음(처음 요청과 그 뒤 자동 재분석)이 공유한다.
    @Column(nullable = false, length = 64)
    private String chainId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private VideoAnalysisRequestedBy requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VideoAnalysisStatus status;

    // 보낸 본문 그대로. 같은 요청 id로 다시 보낼 때 이것만 보낸다. 보내기 전에 걸러 닫은 행은 비어 있다.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> requestPayload;

    @Column(nullable = false)
    @Builder.Default
    private Integer sendCount = 0;

    private LocalDateTime nextCheckAt;

    // 검증을 통과한 결과 본문 원문.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> result;

    @Column(length = 100)
    private String errorCode;

    private Boolean errorRetryable;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
