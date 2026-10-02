package repit.repit_api_server.domain.userdata.question.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;

/** 사이클 안의 질문 한 건. 세트를 꺼내면 그 세트의 질문 전부에 사용 표시가 붙는다. */
@Entity
@Table(name = "pool_question")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class PoolQuestionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "question_id")
    private Long questionId;

    @Column(nullable = false)
    private Long cycleId;

    @Column(nullable = false)
    private Integer setNo;

    @Column(nullable = false, length = 30)
    private String category;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    // 채점 기준. 모범답안이 아니라 이 값으로 채점한다.
    @Column(nullable = false, columnDefinition = "TEXT")
    private String intention;

    @Column(columnDefinition = "TEXT")
    private String expectedAnswer;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> basedOn;

    private LocalDateTime usedAt;

    private Long interviewId;
}
