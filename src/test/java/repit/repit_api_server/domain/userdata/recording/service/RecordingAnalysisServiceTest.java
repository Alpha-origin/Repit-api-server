package repit.repit_api_server.domain.userdata.recording.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import repit.repit_api_server.domain.userdata.answer.entity.AnswerEntity;
import repit.repit_api_server.domain.userdata.answer.repository.AnswerRepository;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.question.entity.QuestionEntity;
import repit.repit_api_server.domain.userdata.question.entity.enums.Type;
import repit.repit_api_server.domain.userdata.question.repository.QuestionRepository;
import repit.repit_api_server.domain.userdata.recording.dto.request.RecordingAnalysisRequest;
import repit.repit_api_server.domain.userdata.recording.dto.response.RecordingAnalysisAcceptedResponse;
import repit.repit_api_server.domain.userdata.recording.entity.InterviewRecordingEntity;
import repit.repit_api_server.domain.userdata.recording.entity.RecordingAnalysisEntity;
import repit.repit_api_server.domain.userdata.recording.entity.enums.RecordingAnalysisStatus;
import repit.repit_api_server.domain.userdata.recording.repository.InterviewRecordingRepository;
import repit.repit_api_server.domain.userdata.recording.repository.RecordingAnalysisRepository;
import repit.repit_api_server.global.client.AiServerClient;
import repit.repit_api_server.global.exception.ExternalApiException;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 질문·답변과 답변 영상이 모두 모인 뒤 면접당 한 번 분석 서버로 넘기는지.
 *
 * <p>두 재료는 채팅 서버와 웹에서 따로, 순서 없이 들어온다. 어느 쪽이 먼저 와도 다 모이는 순간
 * 보내야 하고, 영상이 끝내 덜 모이면 조용해진 뒤에 있는 것만 보내야 한다. 같은 면접을 두 번
 * 보내서도 안 된다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RecordingAnalysisServiceTest {

    private static final Long INTERVIEW_ID = 42L;
    private static final Long ANALYSIS_ID = 9L;
    private static final String CALLBACK_BASE = "https://api.repit.test";

    // 채팅 서버 질문 번호 -> 우리 PK. 원질문 1 -> 101, 그 꼬리질문 -5 -> 102.
    private static final QuestionEntity ORIGINAL = question(101L, 1L, null, Type.ORIGINAL);
    private static final QuestionEntity FOLLOW = question(102L, -5L, 101L, Type.FOLLOW);

    @Mock
    private RecordingAnalysisRepository analysisRepository;
    @Mock
    private InterviewRecordingRepository recordingRepository;
    @Mock
    private InterviewRepository interviewRepository;
    @Mock
    private QuestionRepository questionRepository;
    @Mock
    private AnswerRepository answerRepository;
    @Mock
    private AiServerClient aiServerClient;

    // 서명은 네트워크 없이 로컬에서 계산된다. 실제 서명기로 주소 모양까지 본다.
    private S3Presigner s3Presigner;
    private RecordingAnalysisService service;

    /** DB에 있는 녹화 분석 행 하나. 없으면 null. */
    private RecordingAnalysisEntity stored;
    private final List<InterviewRecordingEntity> recordings = new ArrayList<>();

    @BeforeEach
    void setUp() {
        s3Presigner = S3Presigner.builder()
                .region(Region.AP_NORTHEAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIATEST", "secret")))
                .build();
        service = new RecordingAnalysisService(analysisRepository, recordingRepository, interviewRepository,
                questionRepository, answerRepository, aiServerClient, s3Presigner);
        ReflectionTestUtils.setField(service, "bucketName", "repit-bucket");
        ReflectionTestUtils.setField(service, "callbackBaseUrl", CALLBACK_BASE);
        ReflectionTestUtils.setField(service, "uploadGrace", Duration.ofMinutes(2));
        ReflectionTestUtils.setField(service, "videoUrlTtl", Duration.ofHours(6));

        when(interviewRepository.findById(INTERVIEW_ID)).thenReturn(Optional.of(InterviewEntity.builder()
                .interviewId(INTERVIEW_ID).userId(7L).sessionId("sess-1")
                .mode(InterviewMode.SOLO).status(Status.COMPLETED).build()));
        when(questionRepository.findAllByInterviewId(INTERVIEW_ID)).thenReturn(List.of(ORIGINAL, FOLLOW));
        when(questionRepository.findAllByInterviewIdOrderByQuestionIdAsc(INTERVIEW_ID)).thenReturn(List.of(ORIGINAL, FOLLOW));
        List<AnswerEntity> answers = List.of(answer(201L, 101L), answer(202L, 102L));
        when(answerRepository.findAllByInterviewId(INTERVIEW_ID)).thenReturn(answers);
        when(answerRepository.findAllByInterviewIdOrderByAnswerIdAsc(INTERVIEW_ID)).thenReturn(answers);
        when(recordingRepository.findAllByInterviewIdOrderByRecordingIdAsc(INTERVIEW_ID)).thenAnswer(i -> List.copyOf(recordings));

        // 저장소를 행 하나짜리 DB처럼 흉내 낸다. 조건부 갱신도 실제 쿼리와 같은 조건으로 판단한다.
        when(analysisRepository.findByInterviewId(INTERVIEW_ID)).thenAnswer(i -> Optional.ofNullable(stored));
        when(analysisRepository.findById(ANALYSIS_ID)).thenAnswer(i -> Optional.ofNullable(stored));
        when(analysisRepository.save(any())).thenAnswer(i -> {
            stored = i.getArgument(0);
            ReflectionTestUtils.setField(stored, "analysisId", ANALYSIS_ID);
            return stored;
        });
        when(analysisRepository.touchIfWaiting(eq(INTERVIEW_ID), any()))
                .thenAnswer(i -> stored != null && stored.getStatus() == RecordingAnalysisStatus.WAITING ? 1 : 0);
        when(analysisRepository.claim(ANALYSIS_ID)).thenAnswer(i -> {
            if (stored == null || stored.getStatus() != RecordingAnalysisStatus.WAITING) {
                return 0;
            }
            ReflectionTestUtils.setField(stored, "status", RecordingAnalysisStatus.SENDING);
            return 1;
        });
        when(aiServerClient.requestRecordingAnalysis(any()))
                .thenReturn(new RecordingAnalysisAcceptedResponse("job-1", "accepted", null));
    }

    @AfterEach
    void tearDown() {
        s3Presigner.close();
    }

    @Test
    void 영상이_먼저_다_와_있으면_기록을_받는_순간_보낸다() {
        recordings.add(recording(301L, 1L));
        recordings.add(recording(302L, -5L));

        service.onTranscriptSaved(INTERVIEW_ID);

        RecordingAnalysisRequest request = sentRequest();
        assertThat(request.getInterviewId()).isEqualTo("42");
        assertThat(request.getSessionId()).isEqualTo("sess-1");
        assertThat(request.getMode()).isEqualTo("SOLO");
        assertThat(request.getCallbackUrl()).isEqualTo(CALLBACK_BASE + "/api/interviews/recordings/callback");
        assertThat(request.getQuestions()).extracting(RecordingAnalysisRequest.Question::getQuestionId)
                .containsExactly("101", "102");
        assertThat(request.getQuestions().get(1).getParentId()).isEqualTo("101");
        assertThat(request.getAnswers()).extracting(RecordingAnalysisRequest.Answer::getQuestionId)
                .containsExactly("101", "102");

        // 웹은 채팅 서버 번호를 붙여 보냈다. 분석 서버에는 같은 요청 안의 questionId로 옮겨 나가야 이어 읽을 수 있다.
        assertThat(request.getRecordings()).extracting(RecordingAnalysisRequest.Recording::getQuestionId)
                .containsExactly("101", "102");
        RecordingAnalysisRequest.Recording first = request.getRecordings().getFirst();
        assertThat(first.getContentType()).isEqualTo("video/mp4");
        assertThat(first.getVideoUrl())
                .contains("repit-bucket")
                .contains("interview-recordings/42/301.mp4")
                .contains("X-Amz-Expires=21600");

        assertThat(stored.getStatus()).isEqualTo(RecordingAnalysisStatus.PENDING);
        assertThat(stored.getJobId()).isEqualTo("job-1");
    }

    @Test
    void 기록이_먼저_오면_기다렸다가_마지막_영상이_올라오는_순간_보낸다() {
        recordings.add(recording(301L, 1L));
        service.onTranscriptSaved(INTERVIEW_ID);

        verify(aiServerClient, never()).requestRecordingAnalysis(any());
        assertThat(stored.getStatus()).isEqualTo(RecordingAnalysisStatus.WAITING);

        recordings.add(recording(302L, -5L));
        service.onRecordingUploaded(INTERVIEW_ID);

        verify(aiServerClient).requestRecordingAnalysis(any());
        assertThat(stored.getStatus()).isEqualTo(RecordingAnalysisStatus.PENDING);
    }

    @Test
    void 기록이_오기_전의_업로드는_아무것도_보내지_않는다() {
        recordings.add(recording(301L, 1L));
        recordings.add(recording(302L, -5L));

        service.onRecordingUploaded(INTERVIEW_ID);

        verify(aiServerClient, never()).requestRecordingAnalysis(any());
        verify(analysisRepository, never()).save(any());
    }

    @Test
    void 채팅_서버가_기록을_다시_보내도_두_번_보내지_않는다() {
        recordings.add(recording(301L, 1L));
        recordings.add(recording(302L, -5L));

        service.onTranscriptSaved(INTERVIEW_ID);
        service.onTranscriptSaved(INTERVIEW_ID);

        verify(aiServerClient).requestRecordingAnalysis(any());
    }

    @Test
    void 다른_쪽이_먼저_차지했으면_보내지_않는다() {
        recordings.add(recording(301L, 1L));
        recordings.add(recording(302L, -5L));
        when(analysisRepository.claim(ANALYSIS_ID)).thenReturn(0);

        service.onTranscriptSaved(INTERVIEW_ID);

        verify(aiServerClient, never()).requestRecordingAnalysis(any());
    }

    @Test
    void 보낸_뒤에_늦게_올라온_영상은_다시_보내지_않는다() {
        recordings.add(recording(301L, 1L));
        recordings.add(recording(302L, -5L));
        service.onTranscriptSaved(INTERVIEW_ID);

        recordings.add(recording(303L, 1L));
        service.onRecordingUploaded(INTERVIEW_ID);

        verify(aiServerClient).requestRecordingAnalysis(any());
    }

    @Test
    void 영상이_덜_모인_채_조용해지면_스윕이_있는_것만_보낸다() {
        // 꼬리질문은 텍스트로 답해 영상이 오지 않는다.
        recordings.add(recording(301L, 1L));
        service.onTranscriptSaved(INTERVIEW_ID);
        when(analysisRepository.findAllByStatusAndLastActivityAtBefore(eq(RecordingAnalysisStatus.WAITING), any()))
                .thenReturn(List.of(stored));

        service.sweepQuietInterviews();

        assertThat(sentRequest().getRecordings()).extracting(RecordingAnalysisRequest.Recording::getRecordingId)
                .containsExactly("301");
        assertThat(stored.getStatus()).isEqualTo(RecordingAnalysisStatus.PENDING);
    }

    @Test
    void 조용해질_때까지_영상이_하나도_없으면_보내지_않고_건너뛴다() {
        service.onTranscriptSaved(INTERVIEW_ID);
        when(analysisRepository.findAllByStatusAndLastActivityAtBefore(eq(RecordingAnalysisStatus.WAITING), any()))
                .thenReturn(List.of(stored));

        service.sweepQuietInterviews();

        verify(aiServerClient, never()).requestRecordingAnalysis(any());
        assertThat(stored.getStatus()).isEqualTo(RecordingAnalysisStatus.SKIPPED);
    }

    @Test
    void 분석_서버가_거절하면_실패로_남기고_예외를_올리지_않는다() {
        recordings.add(recording(301L, 1L));
        recordings.add(recording(302L, -5L));
        when(aiServerClient.requestRecordingAnalysis(any()))
                .thenThrow(new ExternalApiException("요청한 AI 리소스를 찾을 수 없습니다.", HttpStatus.NOT_FOUND, null));

        assertThatCode(() -> service.onTranscriptSaved(INTERVIEW_ID)).doesNotThrowAnyException();

        assertThat(stored.getStatus()).isEqualTo(RecordingAnalysisStatus.FAILED);
        assertThat(stored.getErrorMessage()).isEqualTo("요청한 AI 리소스를 찾을 수 없습니다.");
    }

    @Test
    void 답변이_하나도_없으면_곧바로_보내지_않고_스윕을_기다린다() {
        when(answerRepository.findAllByInterviewId(INTERVIEW_ID)).thenReturn(List.of());
        recordings.add(recording(301L, 1L));

        service.onTranscriptSaved(INTERVIEW_ID);

        verify(aiServerClient, never()).requestRecordingAnalysis(any());
        verify(analysisRepository, never()).claim(anyLong());
    }

    private RecordingAnalysisRequest sentRequest() {
        ArgumentCaptor<RecordingAnalysisRequest> captor = ArgumentCaptor.forClass(RecordingAnalysisRequest.class);
        verify(aiServerClient).requestRecordingAnalysis(captor.capture());
        return captor.getValue();
    }

    private static QuestionEntity question(Long id, Long chatId, Long parentId, Type type) {
        return QuestionEntity.builder()
                .questionId(id).interviewId(INTERVIEW_ID).chatQuestionId(chatId)
                .parentId(parentId).type(type).content("질문 " + id).createdAt(LocalDateTime.now())
                .build();
    }

    private static AnswerEntity answer(Long id, Long questionId) {
        return AnswerEntity.builder()
                .answerId(id).interviewId(INTERVIEW_ID).questionId(questionId).userId(7L)
                .content("답변 " + id).responseTime(30).createdAt(LocalDateTime.now())
                .build();
    }

    private static InterviewRecordingEntity recording(Long id, Long chatQuestionId) {
        return InterviewRecordingEntity.builder()
                .recordingId(id).interviewId(INTERVIEW_ID).userId(7L).chatQuestionId(chatQuestionId)
                .s3Key("interview-recordings/42/" + id + ".mp4").fileSize(1024L).createdAt(LocalDateTime.now())
                .build();
    }
}
