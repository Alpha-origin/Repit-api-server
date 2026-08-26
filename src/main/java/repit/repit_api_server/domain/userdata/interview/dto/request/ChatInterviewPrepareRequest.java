package repit.repit_api_server.domain.userdata.interview.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;

import java.util.List;

/**
 * 채팅 서버에 면접을 여는 요청.
 * 질문 재작성이 끝난 뒤 이 서버가 DB에 모아둔 값을 넘긴다.
 *
 * <p>필드는 채팅 서버가 받는 형태에 맞춘다. 채팅 서버는 모르는 필드를 버리므로, 이름이 하나만
 * 어긋나도 거절당하지 않고 그 값만 조용히 사라진다. 넘길 값이 늘어야 한다면 채팅 서버 쪽 수신
 * 형태를 먼저 넓히고 여기를 맞춘다.
 *
 * <p>면접관 성향(personaType)은 채팅 서버가 받지 않아 넘기지 않는다. 어조를 성향으로 정하게
 * 하려면 채팅 서버가 그 필드를 받도록 먼저 넓혀야 한다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatInterviewPrepareRequest {
    private String sessionId;
    private Long interviewId;
    private Long userId;
    // 채팅 서버가 세션에 그대로 싣는 진행 상태. 비면 채팅 서버가 본문을 반려한다.
    private Status status;
    private List<Question> questions;

    /**
     * 면접 질문 한 건.
     *
     * <p>채팅 서버가 면접 내내 들고 다니는 모양이다. 면접이 끝나고 피드백을 요청할 때
     * 이 값들이 그대로 분석 서버로 넘어가므로, 여기서 비워 보내면 되찾을 길이 없다.
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Question {
        private Long id;
        // 이 질문으로 무엇을 확인하려는지. 채팅 서버는 이 필드를 질문의 의도로 읽어 세션에 싣는다.
        private String category;
        // 면접에서 실제로 물을 본문. 재작성본이거나, 폴백이면 원질문과 같다.
        private String question;
        // 이 질문을 던지는 면접관. 1:1은 모든 질문이 같은 면접관이다.
        private Long personaId;
    }
}
