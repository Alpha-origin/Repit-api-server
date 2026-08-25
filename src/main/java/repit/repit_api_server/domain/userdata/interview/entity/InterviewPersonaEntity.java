package repit.repit_api_server.domain.userdata.interview.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * 면접에 들어가는 면접관 한 명.
 *
 * <p>N:1 면접은 면접관이 여럿이라 {@code interview.persona_id} 한 칸으로는 담을 수 없다.
 * {@code personaOrder}가 면접 진행 순서이고, 질문 배열도 이 순서를 따른다.
 */
@Entity
@Table(name = "interview_persona")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class InterviewPersonaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "interview_persona_id")
    private Long interviewPersonaId;

    @Column(nullable = false)
    private Long interviewId;

    @Column(nullable = false)
    private Long personaId;

    // 0부터 시작하는 진행 순서. 기술 -> 인사 -> CEO.
    @Column(nullable = false)
    private Integer personaOrder;
}
