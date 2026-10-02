package repit.repit_api_server.domain.userdata.question.entity;

import jakarta.persistence.*;
import lombok.*;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.question.entity.enums.CycleStatus;

import java.time.LocalDateTime;

/**
 * 종합 데이터 하나로 만든 질문 묶음 한 번. 세트 3개로 나뉘고 면접 하나가 세트 하나를 쓴다.
 *
 * <p>같은 종합 데이터·모드 안에서 cycle_no가 1부터 늘어난다. 지금 쓰는 사이클의 2번째 세트를 꺼낼 때
 * 다음 사이클을 미리 만들어 둔다.
 */
@Entity
@Table(name = "question_cycle")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class QuestionCycleEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "cycle_id")
    private Long cycleId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String profileJobId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private InterviewMode mode;

    @Column(nullable = false)
    private Integer cycleNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CycleStatus status;

    private String jobId;

    @Column(nullable = false)
    @Builder.Default
    private Integer attempt = 1;

    @Column(nullable = false)
    private LocalDateTime requestedAt;

    private LocalDateTime completedAt;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;
}
