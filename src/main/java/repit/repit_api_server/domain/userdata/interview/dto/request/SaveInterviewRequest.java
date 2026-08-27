package repit.repit_api_server.domain.userdata.interview.dto.request;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 채팅 서버가 면접을 마치고 넘겨주는 전체 기록.
 * 완료·중단·마지막 답변 제출 세 경로에서 모두 이 본문이 온다.
 *
 * <p>필드는 채팅 서버가 보내는 형태에 맞춘다. 이름이 어긋나면 그 값만 조용히 null이 되고,
 * 면접 내용이 통째로 사라진 채 저장이 끝난다.
 *
 * <p>채팅 서버는 이 요청을 보낸 직후 세션을 지운다. 여기서 저장에 실패하면 면접 기록을
 * 되찾을 길이 없고, 채팅 서버 쪽 완료 처리까지 예외로 끝나 사용자에게는 "면접 완료"가
 * 실패로 보인다.
 *
 * <p>시각은 오프셋 없는 LocalDateTime으로 온다. 채팅 서버가 LocalDateTime.now()로 찍는다.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class SaveInterviewRequest {
    private String sessionId;
    private Long interviewId;
    private Long userId;
    private Status status;
    private LocalDateTime interviewCreatedAt;
    private List<QnA> qnaRequests;

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QnA {
        private Question question;
        // 답하지 않고 넘어간 질문은 비어 있다.
        private Answer answer;
    }

    /**
     * 면접에서 실제로 오간 질문 한 건.
     *
     * <p>면접을 열 때 우리가 넘긴 필드가 그대로 돌아온다. 꼬리질문은 채팅 서버가 면접 중에
     * 만든 것이라 우리가 넘긴 적 없는 값이고, 기대 답변과 근거가 비어 있다.
     *
     * <p>질문 종류(ORIGINAL/FOLLOW)와 부모 질문, 생성 시각은 채팅 서버가 보내지 않는다.
     * 셋 다 저장할 때 우리가 되짚어 채운다 — {@code InterviewService.saveQuestions} 참고.
     */
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Question {
        // 채팅 서버가 매긴 번호. 우리 PK와는 다른 체계다. 꼬리질문은 음수로 온다.
        private Long id;
        private Long personaId;
        // 이 질문이 무엇을 묻는 갈래인지. 꼬리질문은 채팅 서버가 만든 의도가 실려 온다.
        private String category;
        private String question;
        // 채점 기준. 면접을 열 때 우리가 넘긴 값이 손대지 않은 채 돌아온다.
        private String expectedAnswer;
        private List<String> basedOn;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Answer {
        // 어느 질문에 대한 답인지. 채팅 서버 번호다.
        private Long questionId;
        private Integer responseTime;
        private String answerContent;
        private LocalDateTime answerCreatedAt;
    }
}
