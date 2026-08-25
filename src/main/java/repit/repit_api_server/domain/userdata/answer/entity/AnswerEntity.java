package repit.repit_api_server.domain.userdata.answer.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "interview_answer")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnswerEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "answer_id")
    private Long answerId;

    @Column(nullable = false)
    private Long interviewId;

    @Column(nullable = false)
    private Long questionId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private int responseTime;

    // 모의면접 답변은 길다. 255자로 자르면 그대로 피드백 품질이 깎인다.
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
