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
 * <p>필드는 채팅 서버가 받는 형태에 맞춘다. 채팅 서버는 모르는 필드를 버리므로, 더 실어 보내도
 * 거절당하지는 않지만 아무 데도 쓰이지 않는다. 넘길 값이 늘어야 한다면 채팅 서버 쪽 수신 형태를
 * 먼저 넓히고 여기를 맞춘다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatInterviewPrepareRequest {
    private String sessionId;
    private Long interviewId;
    private Long userId;
    private Status status;
    private List<Question> questions;

    /** 면접 질문 한 건. 채팅 서버는 세 값 모두를 필수로 받는다. */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Question {
        private Long id;
        private String category;
        // 면접에서 실제로 물을 본문. 재작성본이거나, 폴백이면 원질문과 같다.
        private String question;
        // 이 질문을 던질 면접관.
        private Long personaId;
    }
}
