package repit.repit_api_server.domain.userdata.analysis.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import repit.repit_api_server.domain.userdata.analysis.entity.enums.AudioAnalysisStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 분석 서버에 맡긴 음성 분석 요청 하나.
 *
 * <p>분석 서버는 한 번에 녹음 12개까지 받으므로, 답변 음성이 많은 면접은 여러 행으로 나뉜다.
 * 결과는 녹음별로 {@link AudioAnalysisResultEntity}에 남는다.
 */
@Entity
@Table(name = "audio_analysis")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class AudioAnalysisEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "analysis_id")
    private Long analysisId;

    @Column(nullable = false)
    private Long interviewId;

    @Column(nullable = false)
    private Long userId;

    @Column(length = 64)
    private String sessionId;

    // 이 서버가 만든 요청 id. 콜백이 접수 응답보다 먼저 와도 이 값으로 되짚는다.
    @Column(nullable = false, length = 64, unique = true)
    private String requestId;

    // 분석 서버가 접수하며 발급한 작업 id.
    @Column(unique = true)
    private String jobId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AudioAnalysisStatus status;

    // 이 요청에 실은 녹음. 다시 요청할 때 이미 맡긴 녹음을 빼는 데 쓴다.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<Long> recordingIds;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
