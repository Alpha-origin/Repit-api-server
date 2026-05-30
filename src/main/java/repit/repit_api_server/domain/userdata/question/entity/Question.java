package repit.repit_api_server.domain.userdata.question.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
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

    @JoinColumn
    @ManyToOne(fetch = FetchType.LAZY)
    private Interview interviewId;

    @JoinColumn()
    private Long parentId;

    @Column()
    private Type type;

    @Column()
    private String intention;

    @Column()
    private String content;

    @Column()
    private LocalDateTime createdAt;
}
