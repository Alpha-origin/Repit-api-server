package repit.repit_api_server.domain.userdata.interview.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import repit.repit_api_server.domain.userdata.feedback.service.FeedbackDispatchService;
import repit.repit_api_server.domain.userdata.interview.dto.request.SaveInterviewRequest;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;

/**
 * 채팅 서버가 면접을 마치고 넘겨주는 기록을 받는 자리. 기록을 저장하고, 정상 완료된 면접이면 이어서 채점을 준비한다.
 *
 * <p>채점 요청을 {@code InterviewService.saveInterview} 안에 두지 않는 이유는 두 가지다. 저장은
 * 트랜잭션 안에서 이뤄지는데 채점 접수는 분석 서버를 부르는 느린 HTTP 호출이라, 같이 묶으면 그
 * 응답을 기다리는 내내 DB 커넥션을 붙잡는다. 그리고 같은 빈 안에서 부르면 프록시를 타지 않아
 * 저장이 트랜잭션 없이 실행된다. 그래서 저장이 커밋된 뒤 밖에서 채점을 부른다.
 *
 * <p>채점은 곧바로 나가지 않을 수 있다. 답변 영상이 채점 재료라 웹이 아직 올리는 중이면
 * {@link FeedbackDispatchService}가 영상이 모일 때까지 미룬다.
 */
@Service
@RequiredArgsConstructor
public class ChatInterviewResultService {

    private static final Logger log = LoggerFactory.getLogger(ChatInterviewResultService.class);

    private final InterviewService interviewService;
    private final FeedbackDispatchService feedbackDispatchService;

    /**
     * 저장이 먼저고 채점이 나중이다. 채점은 우리 DB에 저장된 질문과 답변을 읽어 분석 서버로
     * 보내므로, 저장이 끝나기 전에 부르면 채점할 것이 없다.
     *
     * <p>저장 실패는 그대로 올려 보낸다. 채팅 서버가 실패를 알아야 기록을 잃은 것을 알 수 있다.
     * 반대로 채점·음성 분석 준비 실패는 여기서 삼킨다 — 면접 기록은 이미 저장됐고, 이 응답이 실패로 가면
     * 채팅 서버의 면접 완료 처리까지 끊긴다. 채점은 웹에서 다시 요청할 수 있다.
     *
     * <p>채점을 준비할지는 받은 요청의 상태가 아니라 커밋 뒤 DB에서 다시 읽은 상태로 정한다. 이미 COMPLETED로 저장된 면접에
     * ABANDONED가 다시 와도, 그 반대여도 처음 확정된 상태를 따른다. 중단 면접은 기록만 남기고 정상 응답한다 — 실패로 답하면
     * 채팅 서버의 전달 대기열이 같은 기록을 계속 다시 보낸다.
     */
    public void handleResult(SaveInterviewRequest request) {
        interviewService.saveInterview(request);

        // 이미 저장한 기록을 다시 받았어도 부른다. 처음 저장이 커밋된 직후 서버가 내려갔거나 여기서 실패했으면 채점·음성
        // 분석 대기 행이 없고, 스윕은 있는 대기 행만 훑어 아무도 채우지 않는다. 종류마다 한 번만 요청하므로 다시 불러도
        // 중복 요청은 없다.
        Status stored = interviewService.findStoredStatus(request.getInterviewId());
        if (stored != Status.COMPLETED) {
            log.info("정상 완료된 면접이 아니라 채점·분석을 준비하지 않습니다. interviewId={}, 저장된 상태={}, 받은 상태={}",
                    request.getInterviewId(), stored, request.getStatus());
            return;
        }

        try {
            feedbackDispatchService.onTranscriptSaved(request.getInterviewId());
        } catch (RuntimeException e) {
            log.error("면접 기록을 받은 뒤 채점·음성 분석을 준비하지 못했습니다. interviewId={}",
                    request.getInterviewId(), e);
        }
    }
}
