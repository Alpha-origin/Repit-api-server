package repit.repit_api_server.domain.userdata.answer.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "interview_answer")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnswerEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "answer_id")
    private Long answerId;

    @Column(nullable = false)
    private Long interviewId;

    @Column(nullable = false)
    private Long questionId;

    @Column(nullable = false)
    private Long userId;

    // 채팅 서버에서 Integer로 온다. 비어 올 수 있어 int로 받지 않는다 — 그러면 0으로 묻힌다.
    private Integer responseTime;

    // 모의면접 답변은 길다. 255자로 자르면 그대로 피드백 품질이 깎인다.
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    // 채팅 면접 답변은 채팅 서버가 찍은 시각을 그대로 남겨야 한다. 그래서 저장 시점으로
    // 덮어쓰지 않고 넣는 쪽이 채운다.
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
