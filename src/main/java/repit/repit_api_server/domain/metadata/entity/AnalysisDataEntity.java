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

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
