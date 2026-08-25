package repit.repit_api_server.domain.userdata.interview.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;

import java.time.LocalDateTime;

@Entity
@Table(name = "interview")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class InterviewEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "interview_id")
    private Long interviewId;

    @Column(nullable = false)
    private Long userId;

    // 1:1 면접의 면접관. N:1은 면접관이 여럿이라 비어 있고, interview_persona가 대신한다.
    private Long personaId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private InterviewMode mode = InterviewMode.SOLO;

    @Column(nullable = false, unique = true, length = 64)
    private String sessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
