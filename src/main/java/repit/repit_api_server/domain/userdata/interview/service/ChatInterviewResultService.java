package repit.repit_api_server.domain.userdata.interview.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import repit.repit_api_server.domain.userdata.feedback.service.FeedbackService;
import repit.repit_api_server.domain.userdata.interview.dto.request.SaveInterviewRequest;
import repit.repit_api_server.global.exception.BusinessException;

/**
 * 채팅 서버가 면접을 마치고 넘겨주는 기록을 받는 자리. 기록을 저장하고, 이어서 채점을 접수한다.
 *
 * <p>채점 요청을 {@code InterviewService.saveInterview} 안에 두지 않는 이유는 두 가지다. 저장은
 * 트랜잭션 안에서 이뤄지는데 채점 접수는 분석 서버를 부르는 느린 HTTP 호출이라, 같이 묶으면 그
 * 응답을 기다리는 내내 DB 커넥션을 붙잡는다. 그리고 같은 빈 안에서 부르면 프록시를 타지 않아
 * 저장이 트랜잭션 없이 실행된다. 그래서 저장이 커밋된 뒤 밖에서 채점을 부른다.
 */
@Service
@RequiredArgsConstructor
public class ChatInterviewResultService {

    private static final Logger log = LoggerFactory.getLogger(ChatInterviewResultService.class);

    private final InterviewService interviewService;
    private final FeedbackService feedbackService;

    /**
     * 저장이 먼저고 채점이 나중이다. 채점은 우리 DB에 저장된 질문과 답변을 읽어 분석 서버로
     * 보내므로, 저장이 끝나기 전에 부르면 채점할 것이 없다.
     *
     * <p>저장 실패는 그대로 올려 보낸다. 채팅 서버가 실패를 알아야 기록을 잃은 것을 알 수 있다.
     * 반대로 채점 접수 실패는 여기서 삼킨다 — 면접 기록은 이미 저장됐고, 이 응답이 실패로 가면
     * 채팅 서버의 면접 완료 처리까지 끊긴다. 채점은 웹에서 다시 요청할 수 있다.
     */
    public void handleResult(SaveInterviewRequest request) {
        interviewService.saveInterview(request);

        try {
            feedbackService.requestFeedbackForFinishedInterview(request.getInterviewId());
        } catch (BusinessException e) {
            // 채점할 답변이 없는 면접처럼, 분석 서버에 보내기 전에 걸러낸 경우다. 장애가 아니다.
            log.warn("면접 기록을 받았지만 채점을 시작하지 않았습니다. interviewId={}, 사유={}",
                    request.getInterviewId(), e.getMessage());
        } catch (RuntimeException e) {
            log.error("면접 기록을 받은 뒤 채점을 접수하지 못했습니다. interviewId={}",
                    request.getInterviewId(), e);
        }
    }
}
