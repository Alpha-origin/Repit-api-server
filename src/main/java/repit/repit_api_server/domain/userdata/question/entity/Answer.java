package repit.repit_api_server.domain.userdata.question.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import repit.repit_api_server.domain.userdata.interview.entity.Interview;

import java.time.LocalDateTime;

@Entity
@Table(name = "interview_answer")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Answer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "answer_id")
    private Long id;

    @JoinColumn(nullable = false)
    @ManyToOne(fetch = FetchType.LAZY)
    private Interview interview;

    @JoinColumn(nullable = false)
    @OneToOne(fetch = FetchType.LAZY)
    private Question question;

    @JoinColumn(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private int responseTime;

    @Column(nullable = false)
    private String content;

    @Column(nullable = false)
    private LocalDateTime createdAt;
}
