package repit.repit_api_server.domain.userdata.feedback.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import repit.repit_api_server.domain.userdata.feedback.dto.response.CompetencyScoreResponse;

import java.util.List;
import java.util.Map;

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

    // 이 문항의 축별 점수(0..100). 해당 없는 축은 값이 비어 있다.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Integer> axisScores;

    // 이 답변에 이어질 예상 꼬리질문. 이전 피드백이나 만들지 못한 문항은 값이 없다.
    @Column(columnDefinition = "TEXT")
    private String followUpQuestion;

    // 질문이 물은 것에 답했는지(응답 적중 등급 0..4). competency-v1 이전 결과는 값이 없다.
    // 컬럼이 SMALLINT라 타입을 맞춘다. 맞추지 않으면 스키마 검증(ddl-auto: validate)에서 기동이 막힌다.
    @JdbcTypeCode(SqlTypes.SMALLINT)
    private Integer responseLevel;

    // 점수에 들어간 역량마다 등급과 근거 인용. competency-v1 이전 결과는 값이 없다.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<CompetencyScoreResponse> competencies;
}
