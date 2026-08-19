package repit.repit_api_server.domain.userdata.feedback.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import repit.repit_api_server.domain.userdata.feedback.dto.response.FrequentWordResponse;
import repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackStatus;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "feedback")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class FeedbackEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "feedback_id")
    private Long feedbackId;

    @Column(nullable = false)
    private Long interviewId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 64)
    private String sessionId;

    // 분석 서버가 202로 발급한 작업 id. 콜백 매칭에 사용한다.
    @Column(length = 64)
    private String jobId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FeedbackStatus status;

    private Integer totalScore;

    private Integer intentAlignmentScore;

    private Integer reliabilityScore;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> strengths;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> improvements;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<FrequentWordResponse> frequentWords;

    private Integer answeredCount;

    private Integer questionCount;

    private Integer errorStatusCode;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
