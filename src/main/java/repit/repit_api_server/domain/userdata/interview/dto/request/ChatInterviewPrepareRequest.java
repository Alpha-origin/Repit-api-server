package repit.repit_api_server.domain.userdata.interview.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.persona.entity.enums.InterviewTone;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Level;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Major;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Type;

import java.util.List;

/**
 * 채팅 서버에 면접을 여는 요청.
 * 질문 재작성이 끝난 뒤 이 서버가 DB에 모아둔 값을 넘긴다.
 *
 * <p>필드는 채팅 서버가 받는 형태에 맞춘다. 채팅 서버는 모르는 필드를 버리므로, 이름이 하나만
 * 어긋나도 거절당하지 않고 그 값만 조용히 사라진다. 넘길 값이 늘어야 한다면 채팅 서버 쪽 수신
 * 형태를 먼저 넓히고 여기를 맞춘다.
 *
 * <p>면접관 설정 네 가지(성향·어조·전공·난이도)는 채팅 서버가 면접을 열 때 필수로 받는다.
 * 하나라도 비면 채팅 서버가 본문을 통째로 반려한다. enum은 서로 다른 클래스지만 JSON에 나가는
 * 문자열이 같아 그대로 맞물린다.
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
    /**
     * 면접관 성향. 이 면접관이 무엇을 파고드는지를 가리킨다.
     *
     * <p>어조와는 다른 축이다. 꼼꼼한 면접관이 부드럽게 물을 수도 있어 성향에서 어조를 유추하지 않는다.
     */
    private Type personality;
    // 면접관 어조. 같은 성향이어도 몰아붙이는 정도가 다르다.
    private InterviewTone tone;
    /**
     * 기술 면접관의 세부 전공.
     *
     * <p>우리 쪽에서는 인사·CEO 면접관에게 이 값이 없지만 채팅 서버는 필수로 받는다.
     * 비워 보내면 면접이 열리지 않으므로 넘기는 쪽에서 채운다 — {@code ChatInterviewHandoffService} 참고.
     */
    private Major major;
    // 면접 난이도.
    private Level level;
    /**
     * 면접 질문 목록.
     *
     * <p>위의 면접관 설정 네 가지는 면접 하나에 한 벌뿐이다. N:1은 질문마다 면접관이 다르지만
     * 채팅 서버가 최상위에서만 받으므로 진행 순서 첫 면접관의 값이 대표로 나간다.
     */
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
