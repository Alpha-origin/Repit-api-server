package repit.repit_api_server.domain.userdata.question.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import repit.repit_api_server.domain.userdata.question.entity.enums.Type;

import java.time.LocalDateTime;

@Entity
@Table(name = "interview_question")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "question_id")
    private Long questionId;

    @Column(nullable = false)
    private Long interviewId;

    @Column()
    private Long parentId;

    /**
     * 채팅 서버가 이 질문에 매긴 번호. 우리 PK와는 다른 체계라 따로 둔다.
     *
     * <p>ORIGINAL은 분석 결과 안의 지역 번호(1..N)라 면접이 다르면 같은 번호가 다시 나오고,
     * FOLLOW는 채팅 서버가 만든 랜덤 값이다. 면접 안에서만 유일하다.
     * 채팅 면접에서 온 질문에만 값이 있다.
     */
    private Long chatQuestionId;

    // 이 질문을 던진 면접관. 1:1은 면접관이 하나뿐이라 비어 있다.
    private Long personaId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    // 채팅 서버가 만드는 꼬리질문은 의도가 비어 올 수 있다. 여기서 막으면 면접 결과 저장이
    // 통째로 실패하고, 사용자에게는 "면접 완료"가 실패로 보인다.
    @Column(columnDefinition = "TEXT")
    private String intention;

    // 꼬리질문은 LLM이 만든다. 255자로 자르면 그대로 피드백 품질이 깎인다.
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    // 채팅 면접 질문은 채팅 서버가 찍은 시각을 그대로 남겨야 한다. 그래서 저장 시점으로
    // 덮어쓰지 않고 넣는 쪽이 채운다.
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
