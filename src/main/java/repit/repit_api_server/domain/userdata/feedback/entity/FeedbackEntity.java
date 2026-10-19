package repit.repit_api_server.domain.userdata.feedback.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import repit.repit_api_server.domain.userdata.feedback.dto.response.CompanyFitResponse;
import repit.repit_api_server.domain.userdata.feedback.dto.response.FrequentWordResponse;
import repit.repit_api_server.domain.userdata.feedback.dto.response.ScoreBreakdownResponse;
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

    // 점수를 매긴 채점 방식(예: axis-v1). 방식이 바뀌면 점수 분포가 달라져, 버전이 다른 점수끼리는
    // 같은 기준으로 비교하면 안 된다. 산출 근거가 오기 전의 결과는 비어 있다.
    // 넘치는 값은 서비스가 잘라 넣는다. 원래 값은 scoreBreakdown에 남는다.
    @Column(length = 64)
    private String scoringVersion;

    // 종합 점수의 산출 근거. 축별 점수·가중치와 일관성 점수.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private ScoreBreakdownResponse scoreBreakdown;

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

    // 기업 관점 코멘트. 기업을 고르지 않은 면접이거나 분석 서버가 만들지 못했으면 비어 있다. 점수와 무관하다.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private CompanyFitResponse companyFit;

    // 이 채점 작업에 실어 보낸 문항 가운데 답변이 있는 것의 id. 기업 관점 코멘트가 근거로 댈 수 있는 목록이다.
    // 접수할 때 한 번 넣고 엔티티 저장으로는 바꾸지 않는다. 콜백이 먼저 와 만든 행은 FeedbackRepository가 채운다.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", updatable = false)
    private List<String> requestedQuestionIds;

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
