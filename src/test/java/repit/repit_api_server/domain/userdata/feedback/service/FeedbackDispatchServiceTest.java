package repit.repit_api_server.domain.userdata.feedback.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import repit.repit_api_server.domain.userdata.answer.entity.AnswerEntity;
import repit.repit_api_server.domain.userdata.answer.repository.AnswerRepository;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackDispatchEntity;
import repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackDispatchStatus;
import repit.repit_api_server.domain.userdata.feedback.repository.FeedbackDispatchRepository;
import repit.repit_api_server.domain.userdata.question.entity.QuestionEntity;
import repit.repit_api_server.domain.userdata.question.entity.enums.Type;
import repit.repit_api_server.domain.userdata.question.repository.QuestionRepository;
import repit.repit_api_server.domain.userdata.recording.entity.InterviewRecordingEntity;
import repit.repit_api_server.domain.userdata.recording.repository.InterviewRecordingRepository;
import repit.repit_api_server.global.exception.BusinessException;
import repit.repit_api_server.global.exception.ExternalApiException;
import org.springframework.web.client.ResourceAccessException;
import repit.repit_api_server.domain.userdata.feedback.service.FeedbackDispatchService.FailureKind;
import software.amazon.awssdk.core.exception.SdkClientException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static repit.repit_api_server.domain.userdata.feedback.service.FeedbackDispatchService.classify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 면접이 끝난 뒤 피드백 요청을 답변 영상이 모일 때까지 미루고, 실패하면 다시 시도하는지.
 *
 * <p>질문·답변과 영상은 채팅 서버와 웹에서 따로, 순서 없이 들어온다. 어느 쪽이 먼저 와도 다
 * 모이는 순간 채점을 요청해야 하고, 영상이 끝내 덜 모이면 조용해진 뒤에 요청해야 한다. 같은 면접을
 * 두 번 요청해서는 안 되고, 일시적인 실패로 자동 채점이 영영 멈춰서도 안 된다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeedbackDispatchServiceTest {

    private static final Long INTERVIEW_ID = 42L;
    private static final Long DISPATCH_ID = 9L;
    private static final int MAX_ATTEMPTS = 3;

    // 채팅 서버 질문 번호 -> 우리 PK. 원질문 1 -> 101, 그 꼬리질문 -5 -> 102.
    private static final QuestionEntity ORIGINAL = question(101L, 1L);
    private static final QuestionEntity FOLLOW = question(102L, -5L);

    @Mock
    private FeedbackDispatchRepository dispatchRepository;
    @Mock
    private InterviewRecordingRepository recordingRepository;
    @Mock
    private QuestionRepository questionRepository;
    @Mock
    private AnswerRepository answerRepository;
    @Mock
    private FeedbackService feedbackService;

    private FeedbackDispatchService service;

    /** DB에 있는 대기 행 하나. 없으면 null. 조건부 갱신도 이 행에 실제 쿼리와 같은 조건으로 적용한다. */
    private FeedbackDispatchEntity stored;
    private final List<InterviewRecordingEntity> recordings = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new FeedbackDispatchService(dispatchRepository, recordingRepository, questionRepository,
                answerRepository, feedbackService);
        ReflectionTestUtils.setField(service, "uploadGrace", Duration.ofMinutes(2));
        ReflectionTestUtils.setField(service, "claimTimeout", Duration.ofMinutes(5));
        ReflectionTestUtils.setField(service, "maxAttempts", MAX_ATTEMPTS);
        ReflectionTestUtils.setField(service, "retryBaseDelay", Duration.ofSeconds(30));
        ReflectionTestUtils.setField(service, "retryMaxDelay", Duration.ofMinutes(10));
        ReflectionTestUtils.setField(service, "unconfirmedRetryDelay", Duration.ofMinutes(10));

        when(questionRepository.findAllByInterviewId(INTERVIEW_ID)).thenReturn(List.of(ORIGINAL, FOLLOW));
        when(answerRepository.findAllByInterviewId(INTERVIEW_ID)).thenReturn(List.of(answer(201L, 101L), answer(202L, 102L)));
        when(recordingRepository.findAllByInterviewIdOrderByRecordingIdAsc(INTERVIEW_ID)).thenAnswer(i -> List.copyOf(recordings));

        when(dispatchRepository.findByInterviewId(INTERVIEW_ID)).thenAnswer(i -> Optional.ofNullable(stored));
        when(dispatchRepository.findById(DISPATCH_ID)).thenAnswer(i -> Optional.ofNullable(stored));
        when(dispatchRepository.save(any())).thenAnswer(i -> {
            stored = i.getArgument(0);
            ReflectionTestUtils.setField(stored, "dispatchId", DISPATCH_ID);
            return stored;
        });
        when(dispatchRepository.touchIfWaiting(eq(INTERVIEW_ID), any()))
                .thenAnswer(i -> is(FeedbackDispatchStatus.WAITING) ? 1 : 0);
        when(dispatchRepository.claim(eq(DISPATCH_ID), any())).thenAnswer(i -> {
            LocalDateTime now = i.getArgument(1);
            if (!is(FeedbackDispatchStatus.WAITING)
                    || (stored.getNextAttemptAt() != null && stored.getNextAttemptAt().isAfter(now))) {
                return 0;
            }
            set("status", FeedbackDispatchStatus.SENDING);
            set("claimedAt", now);
            set("attemptCount", stored.getAttemptCount() + 1);
            return 1;
        });
        when(dispatchRepository.markDone(eq(DISPATCH_ID), any())).thenAnswer(i -> finish(i.getArgument(1), () -> {
            set("status", FeedbackDispatchStatus.DONE);
            set("lastError", null);
        }));
        when(dispatchRepository.markFailed(eq(DISPATCH_ID), any(), anyString())).thenAnswer(i -> finish(i.getArgument(1), () -> {
            set("status", FeedbackDispatchStatus.FAILED);
            set("lastError", i.getArgument(2));
        }));
        when(dispatchRepository.scheduleRetry(eq(DISPATCH_ID), any(), any(), anyString())).thenAnswer(i -> finish(i.getArgument(1), () -> {
            set("status", FeedbackDispatchStatus.WAITING);
            set("claimedAt", null);
            set("nextAttemptAt", i.getArgument(2));
            set("lastError", i.getArgument(3));
        }));
    }

    // ---- 언제 요청하나 ----

    @Test
    void 영상이_먼저_다_와_있으면_기록을_받는_순간_채점을_요청한다() {
        recordAll();

        service.onTranscriptSaved(INTERVIEW_ID);

        verify(feedbackService).requestFeedbackForFinishedInterview(INTERVIEW_ID);
        assertThat(stored.getStatus()).isEqualTo(FeedbackDispatchStatus.DONE);
    }

    @Test
    void 기록이_먼저_오면_기다렸다가_마지막_영상이_올라오는_순간_요청한다() {
        recordings.add(recording(301L, 1L));
        service.onTranscriptSaved(INTERVIEW_ID);

        // 영상이 채점 재료라 덜 모인 채로 요청하면 그 답변은 영상 없이 채점된다.
        verify(feedbackService, never()).requestFeedbackForFinishedInterview(anyLong());
        assertThat(stored.getStatus()).isEqualTo(FeedbackDispatchStatus.WAITING);

        recordings.add(recording(302L, -5L));
        service.onRecordingUploaded(INTERVIEW_ID);

        verify(feedbackService).requestFeedbackForFinishedInterview(INTERVIEW_ID);
        assertThat(stored.getStatus()).isEqualTo(FeedbackDispatchStatus.DONE);
    }

    @Test
    void 기록이_오기_전의_업로드는_아무것도_하지_않는다() {
        recordAll();

        service.onRecordingUploaded(INTERVIEW_ID);

        verify(feedbackService, never()).requestFeedbackForFinishedInterview(anyLong());
        verify(dispatchRepository, never()).save(any());
    }

    @Test
    void 채팅_서버가_기록을_다시_보내도_두_번_요청하지_않는다() {
        recordAll();

        service.onTranscriptSaved(INTERVIEW_ID);
        service.onTranscriptSaved(INTERVIEW_ID);

        verify(feedbackService, times(1)).requestFeedbackForFinishedInterview(INTERVIEW_ID);
    }

    @Test
    void 다른_쪽이_먼저_차지했으면_요청하지_않는다() {
        recordAll();
        when(dispatchRepository.claim(eq(DISPATCH_ID), any())).thenReturn(0);

        service.onTranscriptSaved(INTERVIEW_ID);

        verify(feedbackService, never()).requestFeedbackForFinishedInterview(anyLong());
    }

    @Test
    void 요청한_뒤에_늦게_올라온_영상으로는_다시_요청하지_않는다() {
        recordAll();
        service.onTranscriptSaved(INTERVIEW_ID);

        recordings.add(recording(303L, 1L));
        service.onRecordingUploaded(INTERVIEW_ID);

        verify(feedbackService, times(1)).requestFeedbackForFinishedInterview(INTERVIEW_ID);
    }

    @Test
    void 영상이_덜_모인_채_조용해지면_스윕이_요청한다() {
        // 꼬리질문은 텍스트로 답해 영상이 오지 않는다.
        recordings.add(recording(301L, 1L));
        service.onTranscriptSaved(INTERVIEW_ID);
        dueForSweep();

        service.sweep();

        verify(feedbackService).requestFeedbackForFinishedInterview(INTERVIEW_ID);
        assertThat(stored.getStatus()).isEqualTo(FeedbackDispatchStatus.DONE);
    }

    @Test
    void 영상이_하나도_없어도_조용해지면_채점은_요청한다() {
        service.onTranscriptSaved(INTERVIEW_ID);
        dueForSweep();

        service.sweep();

        // 텍스트로만 답한 면접도 채점 대상이다. 영상은 있으면 싣는 재료일 뿐이다.
        verify(feedbackService).requestFeedbackForFinishedInterview(INTERVIEW_ID);
    }

    @Test
    void 답변이_하나도_없으면_곧바로_요청하지_않고_스윕을_기다린다() {
        when(answerRepository.findAllByInterviewId(INTERVIEW_ID)).thenReturn(List.of());
        recordings.add(recording(301L, 1L));

        service.onTranscriptSaved(INTERVIEW_ID);

        verify(dispatchRepository, never()).claim(anyLong(), any());
    }

    // ---- 실패하면 ----

    @Test
    void 분석_서버_5xx면_닫지_않고_잠시_뒤_다시_시도하게_한다() {
        recordAll();
        doThrow(new ExternalApiException("AI 응답 생성 중 오류가 발생했습니다.", HttpStatus.BAD_GATEWAY, null))
                .when(feedbackService).requestFeedbackForFinishedInterview(INTERVIEW_ID);

        // 여기서 실패를 올리면 영상 업로드나 채팅 서버의 완료 처리가 실패로 끝난다.
        assertThatCode(() -> service.onTranscriptSaved(INTERVIEW_ID)).doesNotThrowAnyException();

        assertThat(stored.getStatus()).isEqualTo(FeedbackDispatchStatus.WAITING);
        assertThat(stored.getNextAttemptAt()).isAfter(LocalDateTime.now().plusSeconds(20));
        assertThat(stored.getLastError()).contains("AI 응답 생성 중 오류");
    }

    @Test
    void 분석_서버가_받지_않은_게_확실한_실패는_곧_다시_시도한다() {
        assertThat(classify(new ExternalApiException("x", HttpStatus.BAD_GATEWAY, null))).isEqualTo(FailureKind.NOT_ACCEPTED);
        assertThat(classify(new ExternalApiException("x", HttpStatus.TOO_MANY_REQUESTS, null))).isEqualTo(FailureKind.NOT_ACCEPTED);
        assertThat(classify(new ExternalApiException("x", HttpStatus.REQUEST_TIMEOUT, null))).isEqualTo(FailureKind.NOT_ACCEPTED);
        assertThat(classify(unreachable(new ConnectException("Connection refused")))).isEqualTo(FailureKind.NOT_ACCEPTED);
        assertThat(classify(unreachable(new UnknownHostException("ai")))).isEqualTo(FailureKind.NOT_ACCEPTED);
        assertThat(classify(SdkClientException.create("presign"))).isEqualTo(FailureKind.NOT_ACCEPTED);
    }

    /** 보냈는데 답을 못 받았으면 분석 서버는 이미 채점을 시작했을 수 있다. 곧바로 다시 보내면 두 번 채점된다. */
    @Test
    void 분석_서버가_받았을_수_있는_실패는_접수_여부를_모른다고_본다() {
        assertThat(classify(unreachable(new IOException("read timed out")))).isEqualTo(FailureKind.UNCONFIRMED);
        assertThat(classify(new ExternalApiException("연결 불가", null, null))).isEqualTo(FailureKind.UNCONFIRMED);
        // DB 오류는 요청 전 읽기에서 났는지 접수 뒤 기록에서 났는지 가를 수 없다.
        assertThat(classify(new DataAccessResourceFailureException("db down"))).isEqualTo(FailureKind.UNCONFIRMED);
    }

    @Test
    void 접수_여부를_모르면_콜백을_기다리는_시간만큼_미룬다() {
        recordAll();
        doThrow(unreachable(new IOException("read timed out")))
                .when(feedbackService).requestFeedbackForFinishedInterview(INTERVIEW_ID);

        service.onTranscriptSaved(INTERVIEW_ID);

        assertThat(stored.getStatus()).isEqualTo(FeedbackDispatchStatus.WAITING);
        // 30초 백오프가 아니라 피드백 콜백 대기 한도(10분) 뒤다.
        assertThat(stored.getNextAttemptAt()).isAfter(LocalDateTime.now().plusMinutes(9));
    }

    /**
     * 첫 요청이 실제로 접수됐다면 기다리는 사이 콜백이 와 피드백 행이 생긴다. 다시 볼 때 FeedbackService는
     * 이미 접수된 채점을 건너뛰므로, 분석 서버에는 한 번만 요청이 간 채로 끝난다.
     */
    @Test
    void 기다린_뒤_다시_볼_때_이미_접수돼_있으면_두_번_요청하지_않고_끝낸다() {
        recordAll();
        doThrow(unreachable(new IOException("read timed out")))
                .doNothing() // 두 번째에는 콜백이 만든 피드백 행을 보고 FeedbackService가 건너뛴다.
                .when(feedbackService).requestFeedbackForFinishedInterview(INTERVIEW_ID);
        service.onTranscriptSaved(INTERVIEW_ID);

        set("nextAttemptAt", LocalDateTime.now().minusSeconds(1));
        dueForSweep();
        service.sweep();

        assertThat(stored.getStatus()).isEqualTo(FeedbackDispatchStatus.DONE);
    }

    @Test
    void 기다린_뒤_스윕이_다시_시도해_성공하면_DONE이다() {
        recordAll();
        doThrow(new ExternalApiException("AI 오류", HttpStatus.INTERNAL_SERVER_ERROR, null))
                .doNothing()
                .when(feedbackService).requestFeedbackForFinishedInterview(INTERVIEW_ID);
        service.onTranscriptSaved(INTERVIEW_ID);

        // 대기 시간이 지나기 전에는 영상이 올라와도 다시 차지하지 않는다.
        service.onRecordingUploaded(INTERVIEW_ID);
        verify(feedbackService, times(1)).requestFeedbackForFinishedInterview(INTERVIEW_ID);

        set("nextAttemptAt", LocalDateTime.now().minusSeconds(1));
        dueForSweep();
        service.sweep();

        verify(feedbackService, times(2)).requestFeedbackForFinishedInterview(INTERVIEW_ID);
        assertThat(stored.getStatus()).isEqualTo(FeedbackDispatchStatus.DONE);
        assertThat(stored.getAttemptCount()).isEqualTo(2);
    }

    @Test
    void 실패가_이어지면_대기_시간이_늘고_한도에서_FAILED로_멈춘다() {
        recordAll();
        doThrow(new ExternalApiException("AI 오류", HttpStatus.INTERNAL_SERVER_ERROR, null))
                .when(feedbackService).requestFeedbackForFinishedInterview(INTERVIEW_ID);

        service.onTranscriptSaved(INTERVIEW_ID);
        LocalDateTime firstRetryAt = stored.getNextAttemptAt();

        set("nextAttemptAt", LocalDateTime.now().minusSeconds(1));
        dueForSweep();
        service.sweep();
        // 두 번째 실패 뒤에는 30초가 아니라 60초를 기다린다.
        assertThat(stored.getNextAttemptAt()).isAfter(firstRetryAt.plusSeconds(20));

        set("nextAttemptAt", LocalDateTime.now().minusSeconds(1));
        service.sweep();

        verify(feedbackService, times(MAX_ATTEMPTS)).requestFeedbackForFinishedInterview(INTERVIEW_ID);
        assertThat(stored.getStatus()).isEqualTo(FeedbackDispatchStatus.FAILED);
        assertThat(stored.getLastError()).contains("시도 한도").contains("AI 오류");
    }

    @Test
    void 대기_시간은_상한을_넘지_않는다() {
        ReflectionTestUtils.setField(service, "maxAttempts", 30);
        service.onTranscriptSaved(INTERVIEW_ID);
        set("attemptCount", 19);
        doThrow(new ExternalApiException("AI 오류", HttpStatus.INTERNAL_SERVER_ERROR, null))
                .when(feedbackService).requestFeedbackForFinishedInterview(INTERVIEW_ID);

        recordAll();
        service.onRecordingUploaded(INTERVIEW_ID);

        assertThat(stored.getAttemptCount()).isEqualTo(20);

        assertThat(stored.getNextAttemptAt()).isBefore(LocalDateTime.now().plusMinutes(11));
    }

    // ---- 요청 도중 끊기면 ----

    @Test
    void 스윕은_먼저_오래된_차지를_되돌린다() {
        service.sweep();

        verify(dispatchRepository).releaseExpiredClaims(any(), anyString());
    }

    @Test
    void 차지만_하고_끊기기를_반복해_한도를_넘으면_요청하지_않고_FAILED로_닫는다() {
        recordAll();
        service.onTranscriptSaved(INTERVIEW_ID);
        verify(feedbackService, times(1)).requestFeedbackForFinishedInterview(INTERVIEW_ID);

        // 끊긴 차지가 되돌려진 상태. 이미 한도만큼 차지됐다.
        set("status", FeedbackDispatchStatus.WAITING);
        set("attemptCount", MAX_ATTEMPTS);
        dueForSweep();

        service.sweep();

        verify(feedbackService, times(1)).requestFeedbackForFinishedInterview(INTERVIEW_ID);
        assertThat(stored.getStatus()).isEqualTo(FeedbackDispatchStatus.FAILED);
    }

    @Test
    void 요청하는_사이_차지가_만료돼_다른_곳이_가져갔으면_결과를_덮지_않는다() {
        recordAll();
        // 요청하는 동안 만료·재차지가 일어나 차지 시각이 바뀐다.
        doThrow(new ExternalApiException("AI 오류", HttpStatus.INTERNAL_SERVER_ERROR, null))
                .when(feedbackService).requestFeedbackForFinishedInterview(INTERVIEW_ID);
        when(dispatchRepository.scheduleRetry(eq(DISPATCH_ID), any(), any(), anyString())).thenAnswer(i -> {
            set("claimedAt", LocalDateTime.now().plusMinutes(6));
            return finish(i.getArgument(1), () -> set("status", FeedbackDispatchStatus.WAITING));
        });

        service.onTranscriptSaved(INTERVIEW_ID);

        assertThat(stored.getStatus()).isEqualTo(FeedbackDispatchStatus.SENDING);
    }

    /** ExternalApiExecutor가 연결 단계 오류를 감싸는 모양 그대로. */
    private static ExternalApiException unreachable(Exception ioCause) {
        return new ExternalApiException("AI 서버와 연결할 수 없습니다.", null,
                new ResourceAccessException("I/O error on POST request", ioCause instanceof IOException io ? io : new IOException(ioCause)));
    }

    private void recordAll() {
        recordings.add(recording(301L, 1L));
        recordings.add(recording(302L, -5L));
    }

    private void dueForSweep() {
        when(dispatchRepository.findDue(any(), any())).thenAnswer(i -> is(FeedbackDispatchStatus.WAITING) ? List.of(stored) : List.of());
    }

    private boolean is(FeedbackDispatchStatus status) {
        return stored != null && stored.getStatus() == status;
    }

    private void set(String field, Object value) {
        ReflectionTestUtils.setField(stored, field, value);
    }

    /** 완료 기록은 내가 차지한 그 건일 때만 반영된다. */
    private int finish(LocalDateTime claimedAt, Runnable apply) {
        if (!is(FeedbackDispatchStatus.SENDING) || !claimedAt.equals(stored.getClaimedAt())) {
            return 0;
        }
        apply.run();
        return 1;
    }

    private static QuestionEntity question(Long id, Long chatId) {
        return QuestionEntity.builder()
                .questionId(id).interviewId(INTERVIEW_ID).chatQuestionId(chatId)
                .type(chatId < 0 ? Type.FOLLOW : Type.ORIGINAL).content("질문 " + id).createdAt(LocalDateTime.now())
                .build();
    }

    private static AnswerEntity answer(Long id, Long questionId) {
        return AnswerEntity.builder()
                .answerId(id).interviewId(INTERVIEW_ID).questionId(questionId).userId(7L)
                .content("답변 " + id).createdAt(LocalDateTime.now())
                .build();
    }

    private static InterviewRecordingEntity recording(Long id, Long chatQuestionId) {
        return InterviewRecordingEntity.builder()
                .recordingId(id).interviewId(INTERVIEW_ID).userId(7L).chatQuestionId(chatQuestionId)
                .s3Key("interview-recordings/42/" + id + ".mp4").fileSize(1024L).createdAt(LocalDateTime.now())
                .build();
    }
}
