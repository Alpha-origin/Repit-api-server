package repit.repit_api_server.domain.userdata.interview.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import repit.repit_api_server.domain.company.dto.CompanySnapshot;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.interview.entity.enums.AnswerMode;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;

import java.time.LocalDateTime;

@Entity
@Table(name = "interview")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class InterviewEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "interview_id")
    private Long interviewId;

    @Column(nullable = false)
    private Long userId;

    // 1:1 면접의 면접관. N:1은 면접관이 여럿이라 비어 있고, interview_persona가 대신한다.
    private Long personaId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private InterviewMode mode = InterviewMode.SOLO;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private AnswerMode answerMode;

    @Column(nullable = false, unique = true, length = 64)
    private String sessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    // 면접을 만들 때 고른 기업. 고르지 않았으면 비어 있다.
    private Long companyId;

    // 만들 때 복사해 둔 기업 정보. 재작성·채팅·피드백 요청은 기업 테이블을 다시 읽지 않고 이것만 쓴다.
    // 기업 정보가 나중에 바뀌어도 이 면접은 만들 때 기준으로 남는다.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private CompanySnapshot companySnapshot;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
