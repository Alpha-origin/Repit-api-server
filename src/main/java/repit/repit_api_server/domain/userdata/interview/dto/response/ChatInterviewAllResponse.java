package repit.repit_api_server.domain.userdata.interview.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 채팅 서버가 내려주는 면접 전체 기록.
 *
 * <p>필드는 채팅 서버가 내려주는 형태에 맞춘다. 우리가 더 선언해 두면 에러 없이 늘 null이 되고,
 * 그 null이 피드백 요청에 실려 분석 서버까지 흘러간다.
 *
 * <p>면접관(personaId·personaType)은 여기에 없다. 채팅 서버는 면접 단위로 면접관을 들고 있지
 * 않고 질문마다 personaId를 달아 둔다. 성향은 애초에 넘긴 적이 없어 우리 DB에서 읽어야 한다.
 *
 * <p>시각은 오프셋 없는 LocalDateTime으로 온다. 채팅 서버가 LocalDateTime.now()로 찍기
 * 때문이다. OffsetDateTime으로 받으면 역직렬화 단계에서 통째로 실패한다.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ChatInterviewAllResponse {
    private String sessionId;
    private Long interviewId;
    private Long userId;
    private Status status;
    private int currentQuestionIndex;
    private LocalDateTime createdAt;
    private List<ChatInterviewQnAResponse> qnAResponses;
}
