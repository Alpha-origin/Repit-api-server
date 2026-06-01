package repit.repit_api_server.domain.userdata.question.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import repit.repit_api_server.domain.userdata.interview.entity.Interview;
import repit.repit_api_server.domain.userdata.question.entity.enums.Type;

import java.time.LocalDateTime;

@Entity
@Table(name = "interview_question")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Question {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "question_id")
    private Long id;

    @JoinColumn(nullable = false)
    @ManyToOne(fetch = FetchType.LAZY)
    private Interview interview;

    @JoinColumn()
    @ManyToOne(fetch = FetchType.LAZY)
    private Question question;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    @Column(nullable = false)
    private String intention;

    @Column(nullable = false)
    private String content;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
