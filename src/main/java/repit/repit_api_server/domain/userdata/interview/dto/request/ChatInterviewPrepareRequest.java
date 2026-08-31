package repit.repit_api_server.domain.userdata.interview.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
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
    /**
     * 면접 방식. 1:1이면 SOLO, 면접관이 교대하는 N:1이면 MULTI다.
     *
     * <p>질문마다 붙는 personaId만으로도 면접관이 바뀌는 것은 보이지만, 그것은 결과를 보고
     * 방식을 되짚는 것이라 질문이 한 명에게 몰린 N:1과 1:1을 구분하지 못한다. 방식은 면접을
     * 열 때 이미 정해져 있으므로 그대로 실어 보낸다.
     */
    private InterviewMode mode;
    private List<Question> questions;

    /**
     * 면접 질문 한 건.
     *
     * <p>채팅 서버가 면접 내내 들고 다니는 모양이다. 면접이 끝나고 기록이 돌아올 때 이 값들이
     * 그대로 실려 오고, 그것이 채점 요청의 재료가 된다. 여기서 비워 보내면 되찾을 길이 없다.
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Question {
        private Long id;
        // 이 질문이 무엇을 묻는 갈래인지. 채팅 서버는 이 값을 질문의 의도로 읽어 꼬리질문을 만들 때 참고한다.
        private String category;
        // 면접에서 실제로 물을 본문. 재작성본이거나, 폴백이면 원질문과 같다.
        private String question;
        /**
         * 이 질문으로 확인하려는 답. 채점의 유일한 기준이라 반드시 실어 보낸다.
         *
         * <p>채팅 서버는 이 값을 손대지 않고 들고 있다가 면접 기록과 함께 그대로 돌려준다.
         * 우리 DB의 질문 의도는 그렇게 돌아온 값으로 채워진다.
         */
        private String expectedAnswer;
        // 이 질문이 어느 근거에서 나왔는지. 채팅 서버가 그대로 되돌려준다.
        private List<String> basedOn;
        // 이 질문을 던지는 면접관. 1:1은 모든 질문이 같은 면접관이다.
        private Long personaId;
    }
}
