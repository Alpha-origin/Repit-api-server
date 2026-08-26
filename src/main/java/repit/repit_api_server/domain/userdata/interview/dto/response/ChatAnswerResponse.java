package repit.repit_api_server.domain.userdata.interview.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 면접 전체 기록에 실려 오는 답변 한 건. 필드는 채팅 서버가 내려주는 형태에 맞춘다.
 *
 * <p>답변 식별자는 없다. 채팅 서버는 답변을 질문에 매달아 들고 있어 따로 번호를 매기지 않는다.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ChatAnswerResponse {
    private Long interviewId;
    private Long questionId;
    private Long userId;
    // 채팅 서버에서 Integer로 온다. 비어 올 수 있어 int로 받지 않는다 — 그러면 0으로 묻힌다.
    private Integer responseTime;
    private String answerContent;
    private LocalDateTime answerCreatedAt;
}
