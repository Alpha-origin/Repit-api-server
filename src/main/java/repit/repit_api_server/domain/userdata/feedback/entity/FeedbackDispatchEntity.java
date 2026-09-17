package repit.repit_api_server.domain.userdata.feedback.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackDispatchStatus;

import java.time.LocalDateTime;

/** 면접이 끝난 뒤 피드백 요청을 답변 영상이 모일 때까지 미뤄두는 자리. 면접당 한 행이다. */
@Entity
@Table(name = "feedback_dispatch")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class FeedbackDispatchEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "dispatch_id")
    private Long dispatchId;

    @Column(nullable = false, unique = true)
    private Long interviewId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private FeedbackDispatchStatus status;

    @Column(nullable = false)
    private LocalDateTime lastActivityAt;

    // 차지할 때마다 하나씩 오른다. 조건부 갱신으로만 바꾼다.
    @Column(nullable = false)
    @Builder.Default
    private Integer attemptCount = 0;

    // SENDING으로 차지한 시각. 이보다 오래 끝나지 않으면 스윕이 WAITING으로 되돌린다.
    private LocalDateTime claimedAt;

    // 일시적 실패 뒤 다시 시도해도 되는 가장 이른 시각. 비어 있으면 곧바로 가능하다.
    private LocalDateTime nextAttemptAt;

    @Column(columnDefinition = "TEXT")
    private String lastError;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
