package repit.repit_api_server.domain.userdata.feedback.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;

@Entity
@Table(name = "feedback_item")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class FeedbackItemEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "feedback_item_id")
    private Long feedbackItemId;

    @Column(nullable = false)
    private Long feedbackId;

    // 분석 서버가 요청값을 그대로 되돌려주는 값이라 형식을 보장하지 않고 문자열로 보관한다.
    @Column(nullable = false, length = 64)
    private String questionId;

    // 분석 서버는 요청의 questions 순서를 유지해서 돌려준다. 그 순서를 그대로 보존한다.
    @Column(nullable = false)
    private Integer sortOrder;

    // 이 질문을 던진 면접관. 1:1 피드백에는 값이 없다.
    private Long personaId;

    @Column(columnDefinition = "TEXT")
    private String questionContent;

    @Column(columnDefinition = "TEXT")
    private String intention;

    @Column(columnDefinition = "TEXT")
    private String userAnswer;

    @Column(columnDefinition = "TEXT")
    private String modelAnswer;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> strengths;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> improvements;

    @Column(columnDefinition = "TEXT")
    private String comment;
}
