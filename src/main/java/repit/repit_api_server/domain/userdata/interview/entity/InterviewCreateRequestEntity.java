package repit.repit_api_server.domain.userdata.interview.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 면접 생성 요청 키. 같은 사용자가 같은 {@code Idempotency-Key}로 다시 보내면 여기 적힌 면접을 돌려준다.
 *
 * <p>면접과 한 트랜잭션에서 저장한다. 면접 없이 남는 키는 없고, 면접을 지우면 함께 지워진다(ON DELETE CASCADE).
 */
@Entity
@Table(name = "interview_create_request")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class InterviewCreateRequestEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "interview_create_request_id")
    private Long interviewCreateRequestId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private UUID idempotencyKey;

    // 정규화한 생성 설정의 SHA-256. 같은 키에 다른 설정이 오면 409로 막는 기준이다.
    @Column(nullable = false, length = 64)
    private String requestHash;

    @Column(nullable = false)
    private Long interviewId;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
