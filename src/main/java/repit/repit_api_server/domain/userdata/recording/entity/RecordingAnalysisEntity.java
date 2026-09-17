package repit.repit_api_server.domain.userdata.recording.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import repit.repit_api_server.domain.userdata.recording.entity.enums.RecordingAnalysisStatus;

import java.time.LocalDateTime;

@Entity
@Table(name = "recording_analysis")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class RecordingAnalysisEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "analysis_id")
    private Long analysisId;

    @Column(nullable = false, unique = true)
    private Long interviewId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RecordingAnalysisStatus status;

    @Column(length = 64)
    private String jobId;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(nullable = false)
    private LocalDateTime lastActivityAt;

    private LocalDateTime requestedAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public void markPending(String jobId) {
        this.status = RecordingAnalysisStatus.PENDING;
        this.jobId = jobId;
        this.requestedAt = LocalDateTime.now();
        this.errorMessage = null;
    }

    public void markFailed(String errorMessage) {
        this.status = RecordingAnalysisStatus.FAILED;
        this.requestedAt = LocalDateTime.now();
        this.errorMessage = errorMessage;
    }

    public void markSkipped() {
        this.status = RecordingAnalysisStatus.SKIPPED;
    }
}
