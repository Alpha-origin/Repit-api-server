package repit.repit_api_server.domain.userdata.feedback.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import repit.repit_api_server.domain.userdata.answer.repository.AnswerRepository;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackDispatchEntity;
import repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackDispatchStatus;
import repit.repit_api_server.domain.userdata.feedback.repository.FeedbackDispatchRepository;
import repit.repit_api_server.domain.userdata.question.entity.QuestionEntity;
import repit.repit_api_server.domain.userdata.question.repository.QuestionRepository;
import repit.repit_api_server.domain.userdata.recording.entity.InterviewRecordingEntity;
import repit.repit_api_server.domain.userdata.recording.repository.InterviewRecordingRepository;
import repit.repit_api_server.global.exception.BusinessException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 면접이 끝난 뒤 피드백 요청을 답변 영상이 모일 때까지 미룬다. 영상은 채점 요청에 함께 실린다.
 *
 * <p>질문·답변은 채팅 서버가 면접을 마칠 때, 영상은 웹이 답변을 마칠 때마다 따로 들어온다.
 * 어느 쪽이 먼저 올지 정해져 있지 않아서 두 입구 모두에서 "다 모였는지"를 본다.
 *
 * <ul>
 *   <li>다 모였다 = 기록이 저장됐고, 답한 질문마다 영상이 있다. 그러면 곧바로 요청한다.</li>
 *   <li>텍스트로 답한 질문은 영상이 영영 오지 않는다. 그래서 마지막으로 무언가 들어온 뒤
 *       유예 시간이 지나면 주기 스윕이 있는 영상만으로 요청한다. 영상이 없어도 채점은 한다.</li>
 * </ul>
 *
 * <p>요청이 실패해도 여기서는 끝난 것으로 둔다. 접수에 실패한 면접은 feedback 행이 남지 않아
 * 웹이 다시 요청할 수 있고, 그 요청도 같은 채점 요청을 만들어 영상을 싣는다.
 */
@Service
@RequiredArgsConstructor
public class FeedbackDispatchService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackDispatchService.class);

    private final FeedbackDispatchRepository dispatchRepository;
    private final InterviewRecordingRepository recordingRepository;
    private final QuestionRepository questionRepository;
    private final AnswerRepository answerRepository;
    private final FeedbackService feedbackService;

    // 기록이 저장된 뒤 영상이 덜 모였을 때, 마지막으로 무언가 들어오고 이만큼 조용하면 있는 영상만 싣고 요청한다.
    @Value("${app.feedback.dispatch.upload-grace:2m}")
    private Duration uploadGrace;

    /** 채팅 서버가 넘긴 질문·답변이 저장된 뒤 부른다. 같은 면접의 기록이 여러 번 와도 한 번만 요청한다. */
    public void onTranscriptSaved(Long interviewId) {
        FeedbackDispatchEntity dispatch = dispatchRepository.findByInterviewId(interviewId).orElse(null);
        if (dispatch == null) {
            dispatch = createWaiting(interviewId);
        } else {
            dispatchRepository.touchIfWaiting(interviewId, LocalDateTime.now());
        }
        dispatchIfComplete(dispatch);
    }

    /** 웹이 영상을 하나 올린 뒤 부른다. 기록이 아직 오지 않았으면 기록이 올 때 함께 본다. */
    public void onRecordingUploaded(Long interviewId) {
        FeedbackDispatchEntity dispatch = dispatchRepository.findByInterviewId(interviewId).orElse(null);
        if (dispatch == null) {
            return;
        }
        if (dispatchRepository.touchIfWaiting(interviewId, LocalDateTime.now()) == 0) {
            // 이미 채점을 요청한 뒤에 도착한 영상이다. 이번 채점에는 들어가지 않는다.
            log.warn("피드백을 이미 요청한 면접에 영상이 늦게 올라왔습니다. interviewId={}, status={}",
                    interviewId, dispatch.getStatus());
            return;
        }
        dispatchIfComplete(dispatch);
    }

    /**
     * 영상이 덜 모인 채 조용해진 면접의 피드백을 요청한다.
     *
     * <p>한 건이 넘어져도 나머지는 계속 보낸다. 실패한 건 하나 때문에 스윕이 멈추면 그 뒤 면접이 모두 매달린다.
     */
    @Scheduled(fixedDelayString = "${app.feedback.dispatch.sweep-interval:30s}")
    public void sweepQuietInterviews() {
        List<FeedbackDispatchEntity> quiet = dispatchRepository.findAllByStatusAndLastActivityAtBefore(
                FeedbackDispatchStatus.WAITING, LocalDateTime.now().minus(uploadGrace));

        for (FeedbackDispatchEntity dispatch : quiet) {
            try {
                dispatch(dispatch.getDispatchId());
            } catch (RuntimeException e) {
                log.error("미뤄둔 피드백을 요청하지 못했습니다. dispatchId={}, interviewId={}",
                        dispatch.getDispatchId(), dispatch.getInterviewId(), e);
            }
        }
    }

    private FeedbackDispatchEntity createWaiting(Long interviewId) {
        try {
            return dispatchRepository.save(FeedbackDispatchEntity.builder()
                    .interviewId(interviewId)
                    .status(FeedbackDispatchStatus.WAITING)
                    .lastActivityAt(LocalDateTime.now())
                    .build());
        } catch (DataIntegrityViolationException e) {
            // 같은 면접의 기록이 동시에 두 번 들어와 다른 쪽이 먼저 만들었다.
            return dispatchRepository.findByInterviewId(interviewId).orElseThrow(() -> e);
        }
    }

    private void dispatchIfComplete(FeedbackDispatchEntity dispatch) {
        if (dispatch.getStatus() != FeedbackDispatchStatus.WAITING) {
            return;
        }
        if (hasRecordingForEveryAnswer(dispatch.getInterviewId())) {
            dispatch(dispatch.getDispatchId());
        }
    }

    /**
     * 답한 질문마다 영상이 있는지.
     *
     * <p>웹은 영상에 채팅 서버 질문 번호를 붙여 보내고, 답변은 우리 질문 PK를 가리킨다. 그래서 질문을
     * 거쳐 같은 번호 체계로 맞춘 뒤 견준다. 답변이 하나도 없으면 무엇을 기다려야 할지 모르므로 스윕에 맡긴다.
     */
    private boolean hasRecordingForEveryAnswer(Long interviewId) {
        Map<Long, Long> chatIdByQuestionId = questionRepository.findAllByInterviewId(interviewId).stream()
                .filter(question -> question.getChatQuestionId() != null)
                .collect(Collectors.toMap(QuestionEntity::getQuestionId, QuestionEntity::getChatQuestionId));

        Set<Long> answeredChatIds = answerRepository.findAllByInterviewId(interviewId).stream()
                .map(answer -> chatIdByQuestionId.get(answer.getQuestionId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (answeredChatIds.isEmpty()) {
            return false;
        }

        Set<Long> recordedChatIds = recordingRepository.findAllByInterviewIdOrderByRecordingIdAsc(interviewId).stream()
                .map(InterviewRecordingEntity::getChatQuestionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return recordedChatIds.containsAll(answeredChatIds);
    }

    private void dispatch(Long dispatchId) {
        if (dispatchRepository.claim(dispatchId) == 0) {
            return;
        }
        FeedbackDispatchEntity dispatch = dispatchRepository.findById(dispatchId).orElseThrow();
        Long interviewId = dispatch.getInterviewId();

        // 채점 접수 실패는 삼킨다. 기록 저장 응답이 실패면 채팅 서버의 완료 처리가 끊기고,
        // 업로드 응답이 실패면 웹이 같은 영상을 또 올린다. 채점은 웹에서 다시 요청할 수 있다.
        try {
            feedbackService.requestFeedbackForFinishedInterview(interviewId);
        } catch (BusinessException e) {
            // 채점할 답변이 없는 면접처럼, 분석 서버에 보내기 전에 걸러낸 경우다. 장애가 아니다.
            log.warn("면접 기록을 받았지만 채점을 시작하지 않았습니다. interviewId={}, 사유={}",
                    interviewId, e.getMessage());
        } catch (RuntimeException e) {
            log.error("면접 기록을 받은 뒤 채점을 접수하지 못했습니다. interviewId={}", interviewId, e);
        }

        dispatch.markDone();
        dispatchRepository.save(dispatch);
    }
}
