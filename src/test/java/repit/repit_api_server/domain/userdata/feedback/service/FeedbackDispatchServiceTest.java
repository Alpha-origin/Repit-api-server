package repit.repit_api_server.domain.userdata.feedback.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
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

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 면접이 끝난 뒤 피드백 요청을 답변 영상이 모일 때까지 미루는지.
 *
 * <p>질문·답변과 영상은 채팅 서버와 웹에서 따로, 순서 없이 들어온다. 어느 쪽이 먼저 와도 다
 * 모이는 순간 채점을 요청해야 하고, 영상이 끝내 덜 모이면 조용해진 뒤에 요청해야 한다. 영상이
 * 없어도 채점은 나가야 하고, 같은 면접을 두 번 요청해서도 안 된다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeedbackDispatchServiceTest {

    private static final Long INTERVIEW_ID = 42L;
    private static final Long DISPATCH_ID = 9L;

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

    /** DB에 있는 대기 행 하나. 없으면 null. */
    private FeedbackDispatchEntity stored;
    private final List<InterviewRecordingEntity> recordings = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new FeedbackDispatchService(dispatchRepository, recordingRepository, questionRepository,
                answerRepository, feedbackService);
        ReflectionTestUtils.setField(service, "uploadGrace", Duration.ofMinutes(2));

        when(questionRepository.findAllByInterviewId(INTERVIEW_ID)).thenReturn(List.of(ORIGINAL, FOLLOW));
        when(answerRepository.findAllByInterviewId(INTERVIEW_ID)).thenReturn(List.of(answer(201L, 101L), answer(202L, 102L)));
        when(recordingRepository.findAllByInterviewIdOrderByRecordingIdAsc(INTERVIEW_ID)).thenAnswer(i -> List.copyOf(recordings));

        // 저장소를 행 하나짜리 DB처럼 흉내 낸다. 조건부 갱신도 실제 쿼리와 같은 조건으로 판단한다.
        when(dispatchRepository.findByInterviewId(INTERVIEW_ID)).thenAnswer(i -> Optional.ofNullable(stored));
        when(dispatchRepository.findById(DISPATCH_ID)).thenAnswer(i -> Optional.ofNullable(stored));
        when(dispatchRepository.save(any())).thenAnswer(i -> {
            stored = i.getArgument(0);
            ReflectionTestUtils.setField(stored, "dispatchId", DISPATCH_ID);
            return stored;
        });
        when(dispatchRepository.touchIfWaiting(eq(INTERVIEW_ID), any()))
                .thenAnswer(i -> stored != null && stored.getStatus() == FeedbackDispatchStatus.WAITING ? 1 : 0);
        when(dispatchRepository.claim(DISPATCH_ID)).thenAnswer(i -> {
            if (stored == null || stored.getStatus() != FeedbackDispatchStatus.WAITING) {
                return 0;
            }
            ReflectionTestUtils.setField(stored, "status", FeedbackDispatchStatus.SENDING);
            return 1;
        });
    }

    @Test
    void 영상이_먼저_다_와_있으면_기록을_받는_순간_채점을_요청한다() {
        recordings.add(recording(301L, 1L));
        recordings.add(recording(302L, -5L));

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
        recordings.add(recording(301L, 1L));
        recordings.add(recording(302L, -5L));

        service.onRecordingUploaded(INTERVIEW_ID);

        verify(feedbackService, never()).requestFeedbackForFinishedInterview(anyLong());
        verify(dispatchRepository, never()).save(any());
    }

    @Test
    void 채팅_서버가_기록을_다시_보내도_두_번_요청하지_않는다() {
        recordings.add(recording(301L, 1L));
        recordings.add(recording(302L, -5L));

        service.onTranscriptSaved(INTERVIEW_ID);
        service.onTranscriptSaved(INTERVIEW_ID);

        verify(feedbackService, times(1)).requestFeedbackForFinishedInterview(INTERVIEW_ID);
    }

    @Test
    void 다른_쪽이_먼저_차지했으면_요청하지_않는다() {
        recordings.add(recording(301L, 1L));
        recordings.add(recording(302L, -5L));
        when(dispatchRepository.claim(DISPATCH_ID)).thenReturn(0);

        service.onTranscriptSaved(INTERVIEW_ID);

        verify(feedbackService, never()).requestFeedbackForFinishedInterview(anyLong());
    }

    @Test
    void 요청한_뒤에_늦게_올라온_영상으로는_다시_요청하지_않는다() {
        recordings.add(recording(301L, 1L));
        recordings.add(recording(302L, -5L));
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
        when(dispatchRepository.findAllByStatusAndLastActivityAtBefore(eq(FeedbackDispatchStatus.WAITING), any()))
                .thenReturn(List.of(stored));

        service.sweepQuietInterviews();

        verify(feedbackService).requestFeedbackForFinishedInterview(INTERVIEW_ID);
        assertThat(stored.getStatus()).isEqualTo(FeedbackDispatchStatus.DONE);
    }

    @Test
    void 영상이_하나도_없어도_조용해지면_채점은_요청한다() {
        service.onTranscriptSaved(INTERVIEW_ID);
        when(dispatchRepository.findAllByStatusAndLastActivityAtBefore(eq(FeedbackDispatchStatus.WAITING), any()))
                .thenReturn(List.of(stored));

        service.sweepQuietInterviews();

        // 텍스트로만 답한 면접도 채점 대상이다. 영상은 있으면 싣는 재료일 뿐이다.
        verify(feedbackService).requestFeedbackForFinishedInterview(INTERVIEW_ID);
    }

    @Test
    void 분석_서버가_실패해도_예외를_올리지_않고_끝낸다() {
        recordings.add(recording(301L, 1L));
        recordings.add(recording(302L, -5L));
        doThrow(new ExternalApiException("AI 응답 생성 중 오류가 발생했습니다.", HttpStatus.INTERNAL_SERVER_ERROR, null))
                .when(feedbackService).requestFeedbackForFinishedInterview(INTERVIEW_ID);

        // 여기서 실패를 올리면 영상 업로드나 채팅 서버의 완료 처리가 실패로 끝난다. 채점은 웹에서 다시 요청할 수 있다.
        assertThatCode(() -> service.onTranscriptSaved(INTERVIEW_ID)).doesNotThrowAnyException();
        assertThat(stored.getStatus()).isEqualTo(FeedbackDispatchStatus.DONE);
    }

    @Test
    void 채점할_것이_없어_거절돼도_끝낸다() {
        recordings.add(recording(301L, 1L));
        recordings.add(recording(302L, -5L));
        doThrow(BusinessException.unprocessable("채점할 답변이 없습니다."))
                .when(feedbackService).requestFeedbackForFinishedInterview(INTERVIEW_ID);

        assertThatCode(() -> service.onTranscriptSaved(INTERVIEW_ID)).doesNotThrowAnyException();
        assertThat(stored.getStatus()).isEqualTo(FeedbackDispatchStatus.DONE);
    }

    @Test
    void 답변이_하나도_없으면_곧바로_요청하지_않고_스윕을_기다린다() {
        when(answerRepository.findAllByInterviewId(INTERVIEW_ID)).thenReturn(List.of());
        recordings.add(recording(301L, 1L));

        service.onTranscriptSaved(INTERVIEW_ID);

        verify(dispatchRepository, never()).claim(anyLong());
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
