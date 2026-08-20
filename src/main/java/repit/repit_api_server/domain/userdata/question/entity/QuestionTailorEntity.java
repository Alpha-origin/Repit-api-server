package repit.repit_api_server.domain.userdata.question.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import repit.repit_api_server.domain.userdata.question.dto.response.TailoredQuestionResponse;
import repit.repit_api_server.domain.userdata.question.entity.enums.TailorStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 면접 시작 요청 한 건에 대응하는 질문 재작성 작업.
 * 콜백은 재작성된 본문만 돌려주므로, 나머지 필드를 복원하려면 요청에 실어보낸 원질문이 필요하다.
 * 그래서 원질문(sourceQuestions)과 면접에 쓸 최종 질문(questions)을 함께 남긴다.
 * 이 두 벌이 그대로 채팅 서버로 넘어간다.
 */
@Entity
@Table(name = "question_tailor")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class QuestionTailorEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "tailor_id")
    private Long tailorId;

    @Column(nullable = false)
    private Long interviewId;

    @Column(nullable = false)
    private Long userId;

    // 분석 서버가 202로 발급한 작업 id. 콜백 매칭에 사용한다.
    @Column(length = 64)
    private String jobId;

    // 원질문이 나온 /generate 작업 id. 채팅 서버는 이 id로만 질문을 조회하므로 병합 키가 된다.
    @Column(length = 64)
    private String analysisJobId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TailorStatus status;

    // 재작성본이면 true, 원질문 폴백이면 false.
    private Boolean tailored;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<TailoredQuestionResponse> sourceQuestions;

    // 면접에 실제로 사용할 최종 질문. 폴백이면 원질문이 그대로 들어간다.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<TailoredQuestionResponse> questions;

    private Integer errorStatusCode;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    // 재작성 결과를 채팅 서버에 넘겼는지. 콜백 재전송으로 두 번 밀어넣지 않으려고 남긴다.
    private Boolean chatDelivered;

    @Column(columnDefinition = "TEXT")
    private String chatErrorMessage;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
