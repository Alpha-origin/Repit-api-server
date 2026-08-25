package repit.repit_api_server.domain.metadata.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import repit.repit_api_server.domain.metadata.entity.enums.AnalysisStatus;

import java.time.LocalDateTime;

@Entity
@Table(name = "analysis_data")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AnalysisDataEntity {
    @Id
    @Column(name = "job_id")
    private String jobId;

    @Column(name = "user_id")
    private Long userId;

    // 콜백이 오기 전에는 PENDING이다. 실패한 작업과 아직 끝나지 않은 작업을 구분하려면 이 값이 필요하다.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private AnalysisStatus status = AnalysisStatus.PENDING;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Object result;

    // 실패 콜백에만 채워진다.
    private Integer errorStatusCode;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    // 콜백을 저장한 시각. 같은 jobId로 분석을 다시 요청했을 때 여기 남은 결과가
    // 이번 실행의 것인지 지난 실행의 것인지는 이 값으로만 가릴 수 있다.
    private LocalDateTime completedAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
