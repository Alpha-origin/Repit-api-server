package repit.repit_api_server.domain.userdata.feedback.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;

/**
 * 면접관 한 명에 대한 종합 피드백. N:1 면접에만 생긴다.
 *
 * <p>면접관당 문항은 원질문 2개 + 꼬리질문 최대 1개다. 그 정도로는 "답변끼리 모순이 없는가"를
 * 판단할 수 없으므로 3지표(total·intent·reliability)는 전체 계층에만 두고 여기는 점수 하나만 둔다.
 */
@Entity
@Table(name = "feedback_persona")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class FeedbackPersonaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "feedback_persona_id")
    private Long feedbackPersonaId;

    @Column(nullable = false)
    private Long feedbackId;

    private Long personaId;

    // 분석 서버가 주는 직책 문자열(TECH/HR/CEO). 값 집합이 서버 간에 어긋나도 저장은 실패하지 않게 문자열로 둔다.
    private String personaRole;

    // 분석 서버가 보낸 personas 순서. 그대로가 면접 진행 순서다.
    @Column(nullable = false)
    private Integer sortOrder;

    private Integer score;

    @Column(columnDefinition = "TEXT")
    private String comment;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> strengths;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> improvements;

    private Integer answeredCount;

    private Integer questionCount;
}
