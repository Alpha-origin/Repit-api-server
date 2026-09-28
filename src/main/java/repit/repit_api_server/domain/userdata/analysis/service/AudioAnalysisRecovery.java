package repit.repit_api_server.domain.userdata.analysis.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import repit.repit_api_server.domain.userdata.analysis.repository.AudioAnalysisRepository;
import repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackDispatchKind;
import repit.repit_api_server.domain.userdata.feedback.repository.FeedbackDispatchRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 결과도 실패도 아닌 채 멈춘 음성 분석 요청을 다시 확인하게 만든다.
 *
 * <p>접수가 성공하면 대기 행은 DONE으로 닫힌다. 그런데 음성 분석은 접수가 끝이 아니라 결과 콜백이 와야
 * 끝이다. 콜백이 유실되면 아무도 그 요청을 다시 보지 않아, 작업을 조회해 결과를 가져오거나 같은 요청
 * id로 접수를 확인하는 코드가 정작 그 상황에서 돌지 않는다.
 *
 * <p>그래서 멈춘 요청이 남은 면접의 대기 행을 기다리는 자리로 되돌린다. 요청을 여기서 직접 보내지는
 * 않는다 — 보내는 일은 한 곳만 해야 한다. 되돌리면 기존 스윕이 차지(claim)하고 요청 경로를 한 번 더
 * 태우므로, 서버가 여러 대여도 같은 면접에 새 요청이 둘 나가지 않는다.
 *
 * <p>시도 한도도 그 장치가 지킨다. 되돌릴 때 시도 횟수를 그대로 두므로, 결과가 영영 오지 않는 면접은
 * 한도에 닿는 순간 FAILED로 닫힌다.
 */
@Component
@RequiredArgsConstructor
public class AudioAnalysisRecovery {

    private static final Logger log = LoggerFactory.getLogger(AudioAnalysisRecovery.class);

    private static final String REASON = "음성 분석 결과를 받지 못해 다시 확인합니다.";

    private final AudioAnalysisRepository analysisRepository;
    private final FeedbackDispatchRepository dispatchRepository;

    // 이 시간을 넘도록 결과도 실패도 아닌 요청을 멈춘 것으로 본다. 요청 경로가 작업을 조회해 볼 시점과 같다.
    @Value("${app.audio-analysis.pending-timeout:10m}")
    private Duration pendingTimeout;

    @Scheduled(fixedDelayString = "${app.audio-analysis.recovery-interval:1m}")
    public void sweep() {
        LocalDateTime checkBefore = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS).minus(pendingTimeout);
        List<Long> interviewIds = analysisRepository.findUnresolvedInterviewIds(checkBefore);

        for (Long interviewId : interviewIds) {
            try {
                // 되돌린 뒤 곧바로 스윕에 걸리도록, 마지막 활동 시각은 그대로 확인 시점으로 둔다.
                int reopened = dispatchRepository.reopen(interviewId, FeedbackDispatchKind.AUDIO_ANALYSIS,
                        checkBefore, REASON);
                if (reopened > 0) {
                    log.info("음성 분석 결과가 오지 않아 다시 확인하도록 되돌렸습니다. interviewId={}", interviewId);
                }
            } catch (RuntimeException e) {
                // 한 건이 넘어져도 나머지는 되돌린다. 여기서 멈추면 그 뒤 면접이 모두 매달린다.
                log.error("멈춘 음성 분석을 되돌리지 못했습니다. interviewId={}", interviewId, e);
            }
        }
    }
}
