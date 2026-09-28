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
import org.springframework.web.client.ResourceAccessException;
import repit.repit_api_server.domain.userdata.analysis.service.AudioAnalysisService;
import repit.repit_api_server.domain.userdata.answer.entity.AnswerEntity;
import repit.repit_api_server.domain.userdata.answer.repository.AnswerRepository;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackDispatchEntity;
import repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackDispatchKind;
import repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackDispatchStatus;
import repit.repit_api_server.domain.userdata.feedback.repository.FeedbackDispatchRepository;
import repit.repit_api_server.domain.userdata.feedback.service.FeedbackDispatchService.FailureKind;
import repit.repit_api_server.domain.userdata.question.entity.QuestionEntity;
import repit.repit_api_server.domain.userdata.question.entity.enums.Type;
import repit.repit_api_server.domain.userdata.question.repository.QuestionRepository;
import repit.repit_api_server.domain.userdata.recording.entity.InterviewRecordingEntity;
import repit.repit_api_server.domain.userdata.recording.entity.enums.RecordingKind;
import repit.repit_api_server.domain.userdata.recording.repository.InterviewRecordingRepository;
import repit.repit_api_server.global.exception.ExternalApiException;
import software.amazon.awssdk.core.exception.SdkClientException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackDispatchKind.AUDIO_ANALYSIS;
import static repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackDispatchKind.FEEDBACK;
import static repit.repit_api_server.domain.userdata.feedback.service.FeedbackDispatchService.classify;

/**
 * 면접이 끝난 뒤 채점과 음성 분석을 제때 요청하고, 실패하면 다시 시도하는지.
 *
 * <p>채점은 질문·답변만 있으면 되므로 기록을 받는 순간 요청해야 한다. 음성 분석은 웹이 따로, 순서 없이
 * 올리는 답변 음성이 모여야 한다 — 어느 쪽이 먼저 와도 다 모이는 순간 요청하고, 끝내 덜 모이면 조용해진 뒤에
 * 요청한다. 같은 일을 두 번 요청해서는 안 되고, 일시적인 실패로 자동 요청이 영영 멈춰서도 안 된다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeedbackDispatchServiceTest {

    private static final Long INTERVIEW_ID = 42L;
    private static final Map<FeedbackDispatchKind, Long> DISPATCH_IDS = Map.of(FEEDBACK, 9L, AUDIO_ANALYSIS, 10L);
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
    @Mock
    private AudioAnalysisService audioAnalysisService;

    private FeedbackDispatchService service;

    /** DB에 있는 대기 행. 종류마다 하나. 조건부 갱신도 이 행에 실제 쿼리와 같은 조건으로 적용한다. */
    private final Map<FeedbackDispatchKind, FeedbackDispatchEntity> rows = new EnumMap<>(FeedbackDispatchKind.class);
    /** 질문별 답변 음성. */
    private final List<InterviewRecordingEntity> recordings = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new FeedbackDispatchService(dispatchRepository, recordingRepository, questionRepository,
                answerRepository, feedbackService, audioAnalysisService);
        ReflectionTestUtils.setField(service, "uploadGrace", Duration.ofMinutes(2));
        ReflectionTestUtils.setField(service, "claimTimeout", Duration.ofMinutes(5));
        ReflectionTestUtils.setField(service, "maxAttempts", MAX_ATTEMPTS);
        ReflectionTestUtils.setField(service, "retryBaseDelay", Duration.ofSeconds(30));
        ReflectionTestUtils.setField(service, "retryMaxDelay", Duration.ofMinutes(10));
        ReflectionTestUtils.setField(service, "unconfirmedRetryDelay", Duration.ofMinutes(10));

        when(questionRepository.findAllByInterviewId(INTERVIEW_ID)).thenReturn(List.of(ORIGINAL, FOLLOW));
        when(answerRepository.findAllByInterviewId(INTERVIEW_ID)).thenReturn(List.of(answer(201L, 101L), answer(202L, 102L)));
        when(recordingRepository.findAllByInterviewIdAndKindOrderByRecordingIdAsc(INTERVIEW_ID, RecordingKind.ANSWER))
                .thenAnswer(i -> List.copyOf(recordings));

        when(dispatchRepository.findByInterviewIdAndKind(eq(INTERVIEW_ID), any()))
                .thenAnswer(i -> Optional.ofNullable(rows.get(i.<FeedbackDispatchKind>getArgument(1))));
        when(dispatchRepository.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(byId(i.getArgument(0))));
        when(dispatchRepository.save(any())).thenAnswer(i -> {
            FeedbackDispatchEntity saved = i.getArgument(0);
            ReflectionTestUtils.setField(saved, "dispatchId", DISPATCH_IDS.get(saved.getKind()));
            rows.put(saved.getKind(), saved);
            return saved;
        });
        when(dispatchRepository.touchIfWaiting(eq(INTERVIEW_ID), any(), any()))
                .thenAnswer(i -> is(i.getArgument(1), FeedbackDispatchStatus.WAITING) ? 1 : 0);
        when(dispatchRepository.claim(anyLong(), any())).thenAnswer(i -> {
            FeedbackDispatchEntity row = byId(i.getArgument(0));
            LocalDateTime now = i.getArgument(1);
            if (row == null || row.getStatus() != FeedbackDispatchStatus.WAITING
                    || (row.getNextAttemptAt() != null && row.getNextAttemptAt().isAfter(now))) {
                return 0;
            }
            set(row, "status", FeedbackDispatchStatus.SENDING);
            set(row, "claimedAt", now);
            set(row, "attemptCount", row.getAttemptCount() + 1);
            return 1;
        });
        when(dispatchRepository.markDone(anyLong(), any())).thenAnswer(i -> finish(i.getArgument(0), i.getArgument(1), row -> {
            set(row, "status", FeedbackDispatchStatus.DONE);
            set(row, "lastError", null);
        }));
        when(dispatchRepository.markFailed(anyLong(), any(), anyString())).thenAnswer(i -> finish(i.getArgument(0), i.getArgument(1), row -> {
            set(row, "status", FeedbackDispatchStatus.FAILED);
            set(row, "lastError", i.getArgument(2));
        }));
        when(dispatchRepository.scheduleRetry(anyLong(), any(), any(), anyString())).thenAnswer(i -> finish(i.getArgument(0), i.getArgument(1), row -> {
            set(row, "status", FeedbackDispatchStatus.WAITING);
            set(row, "claimedAt", null);
            set(row, "nextAttemptAt", i.getArgument(2));
            set(row, "lastError", i.getArgument(3));
        }));
        when(dispatchRepository.findDue(any(), any())).thenAnswer(i -> rows.values().stream()
                .filter(row -> row.getStatus() == FeedbackDispatchStatus.WAITING)
                .filter(row -> row.getLastActivityAt().isBefore(i.getArgument(0)))
                .filter(row -> row.getNextAttemptAt() == null || !row.getNextAttemptAt().isAfter(i.getArgument(1)))
                .toList());
    }

    // ---- 채점: 기록만 있으면 곧바로 ----

    /** 채점은 질문·답변만으로 한다. 음성을 기다리면 텍스트로 답한 면접의 피드백까지 늦어진다. */
    @Test
    void 기록을_받으면_음성을_기다리지_않고_곧바로_채점을_요청한다() {
        service.onTranscriptSaved(INTERVIEW_ID);

        verify(feedbackService).requestFeedbackForFinishedInterview(INTERVIEW_ID);
        assertThat(row(FEEDBACK).getStatus()).isEqualTo(FeedbackDispatchStatus.DONE);
        // 음성은 아직 없으니 음성 분석은 기다린다.
        verify(audioAnalysisService, never()).requestForFinishedInterview(anyLong());
        assertThat(row(AUDIO_ANALYSIS).getStatus()).isEqualTo(FeedbackDispatchStatus.WAITING);
    }

    @Test
    void 채팅_서버가_기록을_다시_보내도_두_번_요청하지_않는다() {
        recordAll();

        service.onTranscriptSaved(INTERVIEW_ID);
        service.onTranscriptSaved(INTERVIEW_ID);

        verify(feedbackService, times(1)).requestFeedbackForFinishedInterview(INTERVIEW_ID);
        verify(audioAnalysisService, times(1)).requestForFinishedInterview(INTERVIEW_ID);
    }

    @Test
    void 답변_음성이_올라와도_채점을_다시_요청하지_않는다() {
        service.onTranscriptSaved(INTERVIEW_ID);

        recordAll();
        service.onRecordingUploaded(INTERVIEW_ID);

        verify(feedbackService, times(1)).requestFeedbackForFinishedInterview(INTERVIEW_ID);
    }

    // ---- 음성 분석: 음성이 모이면 ----

    @Test
    void 답변_음성이_먼저_다_와_있으면_기록을_받는_순간_음성_분석을_요청한다() {
        recordAll();

        service.onTranscriptSaved(INTERVIEW_ID);

        verify(audioAnalysisService).requestForFinishedInterview(INTERVIEW_ID);
        assertThat(row(AUDIO_ANALYSIS).getStatus()).isEqualTo(FeedbackDispatchStatus.DONE);
    }

    @Test
    void 기록이_먼저_오면_기다렸다가_마지막_음성이_올라오는_순간_음성_분석을_요청한다() {
        recordings.add(recording(301L, 1L));
        service.onTranscriptSaved(INTERVIEW_ID);

        // 덜 모인 채로 요청하면 그 답변은 분석되지 않는다.
        verify(audioAnalysisService, never()).requestForFinishedInterview(anyLong());

        recordings.add(recording(302L, -5L));
        service.onRecordingUploaded(INTERVIEW_ID);

        verify(audioAnalysisService).requestForFinishedInterview(INTERVIEW_ID);
        assertThat(row(AUDIO_ANALYSIS).getStatus()).isEqualTo(FeedbackDispatchStatus.DONE);
    }

    @Test
    void 기록이_오기_전의_업로드는_아무것도_하지_않는다() {
        recordAll();

        service.onRecordingUploaded(INTERVIEW_ID);

        verify(audioAnalysisService, never()).requestForFinishedInterview(anyLong());
        verify(dispatchRepository, never()).save(any());
    }

    @Test
    void 요청한_뒤에_늦게_올라온_음성으로는_다시_요청하지_않는다() {
        recordAll();
        service.onTranscriptSaved(INTERVIEW_ID);

        recordings.add(recording(303L, 1L));
        service.onRecordingUploaded(INTERVIEW_ID);

        verify(audioAnalysisService, times(1)).requestForFinishedInterview(INTERVIEW_ID);
    }

    @Test
    void 음성이_덜_모인_채_조용해지면_스윕이_음성_분석을_요청한다() {
        // 꼬리질문은 텍스트로 답해 음성이 오지 않는다.
        recordings.add(recording(301L, 1L));
        service.onTranscriptSaved(INTERVIEW_ID);
        quiet(AUDIO_ANALYSIS);

        service.sweep();

        verify(audioAnalysisService).requestForFinishedInterview(INTERVIEW_ID);
        assertThat(row(AUDIO_ANALYSIS).getStatus()).isEqualTo(FeedbackDispatchStatus.DONE);
    }

    @Test
    void 조용해지기_전에는_스윕도_음성_분석을_요청하지_않는다() {
        recordings.add(recording(301L, 1L));
        service.onTranscriptSaved(INTERVIEW_ID);

        service.sweep();

        verify(audioAnalysisService, never()).requestForFinishedInterview(anyLong());
    }

    @Test
    void 답변이_하나도_없으면_음성_분석은_곧바로_요청하지_않고_스윕을_기다린다() {
        when(answerRepository.findAllByInterviewId(INTERVIEW_ID)).thenReturn(List.of());
        recordings.add(recording(301L, 1L));

        service.onTranscriptSaved(INTERVIEW_ID);

        verify(audioAnalysisService, never()).requestForFinishedInterview(anyLong());
        assertThat(row(AUDIO_ANALYSIS).getStatus()).isEqualTo(FeedbackDispatchStatus.WAITING);
    }

    @Test
    void 다른_쪽이_먼저_차지했으면_요청하지_않는다() {
        recordAll();
        when(dispatchRepository.claim(anyLong(), any())).thenReturn(0);

        service.onTranscriptSaved(INTERVIEW_ID);

        verify(feedbackService, never()).requestFeedbackForFinishedInterview(anyLong());
        verify(audioAnalysisService, never()).requestForFinishedInterview(anyLong());
    }

    // ---- 실패하면 ----

    /** 두 일은 대기 행이 따로다. 한쪽이 실패해도 다른 쪽은 제 갈 길을 간다. */
    @Test
    void 채점_요청이_실패해도_음성_분석은_따로_요청한다() {
        recordAll();
        doThrow(new ExternalApiException("AI 응답 생성 중 오류가 발생했습니다.", HttpStatus.BAD_GATEWAY, null))
                .when(feedbackService).requestFeedbackForFinishedInterview(INTERVIEW_ID);

        service.onTranscriptSaved(INTERVIEW_ID);

        assertThat(row(FEEDBACK).getStatus()).isEqualTo(FeedbackDispatchStatus.WAITING);
        verify(audioAnalysisService).requestForFinishedInterview(INTERVIEW_ID);
        assertThat(row(AUDIO_ANALYSIS).getStatus()).isEqualTo(FeedbackDispatchStatus.DONE);
    }

    @Test
    void 음성_분석_요청이_실패하면_채점과_상관없이_다시_시도하게_한다() {
        recordAll();
        doThrow(new ExternalApiException("AI 오류", HttpStatus.INTERNAL_SERVER_ERROR, null))
                .doNothing()
                .when(audioAnalysisService).requestForFinishedInterview(INTERVIEW_ID);

        service.onTranscriptSaved(INTERVIEW_ID);

        assertThat(row(FEEDBACK).getStatus()).isEqualTo(FeedbackDispatchStatus.DONE);
        assertThat(row(AUDIO_ANALYSIS).getStatus()).isEqualTo(FeedbackDispatchStatus.WAITING);

        set(row(AUDIO_ANALYSIS), "nextAttemptAt", LocalDateTime.now().minusSeconds(1));
        quiet(AUDIO_ANALYSIS);
        service.sweep();

        verify(audioAnalysisService, times(2)).requestForFinishedInterview(INTERVIEW_ID);
        verify(feedbackService, times(1)).requestFeedbackForFinishedInterview(INTERVIEW_ID);
        assertThat(row(AUDIO_ANALYSIS).getStatus()).isEqualTo(FeedbackDispatchStatus.DONE);
    }

    @Test
    void 분석_서버_5xx면_닫지_않고_잠시_뒤_다시_시도하게_한다() {
        doThrow(new ExternalApiException("AI 응답 생성 중 오류가 발생했습니다.", HttpStatus.BAD_GATEWAY, null))
                .when(feedbackService).requestFeedbackForFinishedInterview(INTERVIEW_ID);

        // 여기서 실패를 올리면 채팅 서버의 완료 처리가 실패로 끝난다.
        assertThatCode(() -> service.onTranscriptSaved(INTERVIEW_ID)).doesNotThrowAnyException();

        assertThat(row(FEEDBACK).getStatus()).isEqualTo(FeedbackDispatchStatus.WAITING);
        assertThat(row(FEEDBACK).getNextAttemptAt()).isAfter(LocalDateTime.now().plusSeconds(20));
        assertThat(row(FEEDBACK).getLastError()).contains("AI 응답 생성 중 오류");
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

    /** 보냈는데 답을 못 받았으면 분석 서버는 이미 일을 시작했을 수 있다. 곧바로 다시 보내면 두 번 돈다. */
    @Test
    void 분석_서버가_받았을_수_있는_실패는_접수_여부를_모른다고_본다() {
        assertThat(classify(unreachable(new IOException("read timed out")))).isEqualTo(FailureKind.UNCONFIRMED);
        assertThat(classify(new ExternalApiException("연결 불가", null, null))).isEqualTo(FailureKind.UNCONFIRMED);
        // DB 오류는 요청 전 읽기에서 났는지 접수 뒤 기록에서 났는지 가를 수 없다.
        assertThat(classify(new DataAccessResourceFailureException("db down"))).isEqualTo(FailureKind.UNCONFIRMED);
    }

    @Test
    void 접수_여부를_모르면_콜백을_기다리는_시간만큼_미룬다() {
        doThrow(unreachable(new IOException("read timed out")))
                .when(feedbackService).requestFeedbackForFinishedInterview(INTERVIEW_ID);

        service.onTranscriptSaved(INTERVIEW_ID);

        assertThat(row(FEEDBACK).getStatus()).isEqualTo(FeedbackDispatchStatus.WAITING);
        // 30초 백오프가 아니라 콜백 대기 한도(10분) 뒤다.
        assertThat(row(FEEDBACK).getNextAttemptAt()).isAfter(LocalDateTime.now().plusMinutes(9));
    }

    /**
     * 첫 요청이 실제로 접수됐다면 기다리는 사이 콜백이 와 기록이 생긴다. 다시 볼 때 각 서비스가 이미 접수된
     * 일을 건너뛰므로, 분석 서버에는 한 번만 요청이 간 채로 끝난다.
     */
    @Test
    void 기다린_뒤_다시_볼_때_이미_접수돼_있으면_두_번_요청하지_않고_끝낸다() {
        doThrow(unreachable(new IOException("read timed out")))
                .doNothing() // 두 번째에는 콜백이 만든 피드백 행을 보고 FeedbackService가 건너뛴다.
                .when(feedbackService).requestFeedbackForFinishedInterview(INTERVIEW_ID);
        service.onTranscriptSaved(INTERVIEW_ID);

        set(row(FEEDBACK), "nextAttemptAt", LocalDateTime.now().minusSeconds(1));
        quiet(FEEDBACK);
        service.sweep();

        assertThat(row(FEEDBACK).getStatus()).isEqualTo(FeedbackDispatchStatus.DONE);
    }

    @Test
    void 대기_시간이_지나기_전에는_다시_차지하지_않는다() {
        doThrow(new ExternalApiException("AI 오류", HttpStatus.INTERNAL_SERVER_ERROR, null))
                .when(feedbackService).requestFeedbackForFinishedInterview(INTERVIEW_ID);
        service.onTranscriptSaved(INTERVIEW_ID);

        // 기록이 다시 와도 앞선 실패의 대기 시간은 지킨다.
        service.onTranscriptSaved(INTERVIEW_ID);

        verify(feedbackService, times(1)).requestFeedbackForFinishedInterview(INTERVIEW_ID);
    }

    @Test
    void 실패가_이어지면_대기_시간이_늘고_한도에서_FAILED로_멈춘다() {
        doThrow(new ExternalApiException("AI 오류", HttpStatus.INTERNAL_SERVER_ERROR, null))
                .when(feedbackService).requestFeedbackForFinishedInterview(INTERVIEW_ID);

        service.onTranscriptSaved(INTERVIEW_ID);
        LocalDateTime firstRetryAt = row(FEEDBACK).getNextAttemptAt();

        set(row(FEEDBACK), "nextAttemptAt", LocalDateTime.now().minusSeconds(1));
        quiet(FEEDBACK);
        service.sweep();
        // 두 번째 실패 뒤에는 30초가 아니라 60초를 기다린다.
        assertThat(row(FEEDBACK).getNextAttemptAt()).isAfter(firstRetryAt.plusSeconds(20));

        set(row(FEEDBACK), "nextAttemptAt", LocalDateTime.now().minusSeconds(1));
        service.sweep();

        verify(feedbackService, times(MAX_ATTEMPTS)).requestFeedbackForFinishedInterview(INTERVIEW_ID);
        assertThat(row(FEEDBACK).getStatus()).isEqualTo(FeedbackDispatchStatus.FAILED);
        assertThat(row(FEEDBACK).getLastError()).contains("시도 한도").contains("AI 오류");
    }

    @Test
    void 대기_시간은_상한을_넘지_않는다() {
        ReflectionTestUtils.setField(service, "maxAttempts", 30);
        doThrow(new ExternalApiException("AI 오류", HttpStatus.INTERNAL_SERVER_ERROR, null))
                .when(audioAnalysisService).requestForFinishedInterview(INTERVIEW_ID);
        service.onTranscriptSaved(INTERVIEW_ID);
        set(row(AUDIO_ANALYSIS), "attemptCount", 19);

        recordAll();
        service.onRecordingUploaded(INTERVIEW_ID);

        assertThat(row(AUDIO_ANALYSIS).getAttemptCount()).isEqualTo(20);
        assertThat(row(AUDIO_ANALYSIS).getNextAttemptAt()).isBefore(LocalDateTime.now().plusMinutes(11));
    }

    // ---- 요청 도중 끊기면 ----

    @Test
    void 스윕은_먼저_오래된_차지를_되돌린다() {
        service.sweep();

        verify(dispatchRepository).releaseExpiredClaims(any(), anyString());
    }

    @Test
    void 차지만_하고_끊기기를_반복해_한도를_넘으면_요청하지_않고_FAILED로_닫는다() {
        service.onTranscriptSaved(INTERVIEW_ID);
        verify(feedbackService, times(1)).requestFeedbackForFinishedInterview(INTERVIEW_ID);

        // 끊긴 차지가 되돌려진 상태. 이미 한도만큼 차지됐다.
        set(row(FEEDBACK), "status", FeedbackDispatchStatus.WAITING);
        set(row(FEEDBACK), "attemptCount", MAX_ATTEMPTS);
        quiet(FEEDBACK);

        service.sweep();

        verify(feedbackService, times(1)).requestFeedbackForFinishedInterview(INTERVIEW_ID);
        assertThat(row(FEEDBACK).getStatus()).isEqualTo(FeedbackDispatchStatus.FAILED);
    }

    @Test
    void 요청하는_사이_차지가_만료돼_다른_곳이_가져갔으면_결과를_덮지_않는다() {
        // 요청하는 동안 만료·재차지가 일어나 차지 시각이 바뀐다.
        doThrow(new ExternalApiException("AI 오류", HttpStatus.INTERNAL_SERVER_ERROR, null))
                .when(feedbackService).requestFeedbackForFinishedInterview(INTERVIEW_ID);
        when(dispatchRepository.scheduleRetry(anyLong(), any(), any(), anyString())).thenAnswer(i -> {
            FeedbackDispatchEntity row = byId(i.getArgument(0));
            set(row, "claimedAt", LocalDateTime.now().plusMinutes(6));
            return finish(i.getArgument(0), i.getArgument(1), r -> set(r, "status", FeedbackDispatchStatus.WAITING));
        });

        service.onTranscriptSaved(INTERVIEW_ID);

        assertThat(row(FEEDBACK).getStatus()).isEqualTo(FeedbackDispatchStatus.SENDING);
    }

    /** ExternalApiExecutor가 연결 단계 오류를 감싸는 모양 그대로. */
    private static ExternalApiException unreachable(Exception ioCause) {
        return new ExternalApiException("AI 서버와 연결할 수 없습니다.", null,
                new ResourceAccessException("I/O error on POST request", ioCause instanceof IOException io ? io : new IOException(ioCause)));
    }

    /** 답한 질문마다 음성이 올라온 상태. */
    private void recordAll() {
        recordings.add(recording(301L, 1L));
        recordings.add(recording(302L, -5L));
    }

    /** 마지막 활동 뒤 유예 시간이 지난 상태로 돌린다. */
    private void quiet(FeedbackDispatchKind kind) {
        set(row(kind), "lastActivityAt", LocalDateTime.now().minusMinutes(3));
    }

    private FeedbackDispatchEntity row(FeedbackDispatchKind kind) {
        return rows.get(kind);
    }

    private FeedbackDispatchEntity byId(Long dispatchId) {
        return rows.values().stream().filter(row -> row.getDispatchId().equals(dispatchId)).findFirst().orElse(null);
    }

    private boolean is(FeedbackDispatchKind kind, FeedbackDispatchStatus status) {
        return rows.get(kind) != null && rows.get(kind).getStatus() == status;
    }

    private static void set(FeedbackDispatchEntity row, String field, Object value) {
        ReflectionTestUtils.setField(row, field, value);
    }

    /** 완료 기록은 내가 차지한 그 건일 때만 반영된다. */
    private int finish(Long dispatchId, LocalDateTime claimedAt, Consumer<FeedbackDispatchEntity> apply) {
        FeedbackDispatchEntity row = byId(dispatchId);
        if (row == null || row.getStatus() != FeedbackDispatchStatus.SENDING || !claimedAt.equals(row.getClaimedAt())) {
            return 0;
        }
        apply.accept(row);
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
                .recordingId(id).interviewId(INTERVIEW_ID).userId(7L)
                .kind(RecordingKind.ANSWER).chatQuestionId(chatQuestionId).contentType("audio/mpeg")
                .s3Key("interview-recordings/42/" + id + ".mp3").fileSize(1024L).createdAt(LocalDateTime.now())
                .build();
    }
}
