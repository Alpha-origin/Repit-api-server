package repit.repit_api_server.domain.userdata.analysis.service;

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
import repit.repit_api_server.domain.userdata.analysis.dto.request.AudioAnalysisCallbackRequest;
import repit.repit_api_server.domain.userdata.analysis.dto.request.AudioAnalysisRequest;
import repit.repit_api_server.domain.userdata.analysis.dto.response.AudioAnalysisAcceptedResponse;
import repit.repit_api_server.domain.userdata.analysis.dto.response.AudioAnalysisJobResponse;
import repit.repit_api_server.domain.userdata.analysis.entity.AudioAnalysisEntity;
import repit.repit_api_server.domain.userdata.analysis.entity.AudioAnalysisResultEntity;
import repit.repit_api_server.domain.userdata.analysis.entity.enums.AudioAnalysisStatus;
import repit.repit_api_server.domain.userdata.analysis.repository.AudioAnalysisRepository;
import repit.repit_api_server.domain.userdata.analysis.repository.AudioAnalysisResultRepository;
import repit.repit_api_server.domain.userdata.analysis.service.AudioRecordingLoader.AnswerRecording;
import repit.repit_api_server.domain.userdata.answer.repository.AnswerRepository;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.question.repository.QuestionRepository;
import repit.repit_api_server.domain.userdata.recording.entity.InterviewRecordingEntity;
import repit.repit_api_server.domain.userdata.recording.entity.enums.RecordingEndReason;
import repit.repit_api_server.domain.userdata.recording.entity.enums.RecordingKind;
import repit.repit_api_server.global.client.AiServerClient;
import repit.repit_api_server.global.exception.BusinessException;
import repit.repit_api_server.global.exception.ExternalApiException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 답변 음성을 음성 분석에 맡기고 결과를 받아 두는지.
 *
 * <p>분석 서버는 녹음을 한 요청에 12개까지 받는다. 다시 시도할 때 이미 맡긴 녹음을 또 보내면 같은 답변이
 * 두 번 분석되고, 콜백을 버리면 그 결과는 되찾을 수 없다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AudioAnalysisServiceTest {

    private static final Long INTERVIEW_ID = 42L;

    @Mock
    private AudioAnalysisRepository analysisRepository;
    @Mock
    private AudioAnalysisResultRepository resultRepository;
    @Mock
    private InterviewRepository interviewRepository;
    @Mock
    private QuestionRepository questionRepository;
    @Mock
    private AnswerRepository answerRepository;
    @Mock
    private AudioRecordingLoader recordingLoader;
    @Mock
    private AiServerClient aiServerClient;

    private AudioAnalysisService service;

    /** DB에 있는 음성 분석 요청. */
    private final List<AudioAnalysisEntity> analyses = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new AudioAnalysisService(analysisRepository, resultRepository, interviewRepository,
                questionRepository, answerRepository, recordingLoader, aiServerClient, JsonMapper.builder().build());
        ReflectionTestUtils.setField(service, "callbackBaseUrl", "https://api.repit.test");
        ReflectionTestUtils.setField(service, "pendingTimeout", Duration.ofMinutes(10));
        ReflectionTestUtils.setField(service, "giveUpAfter", Duration.ofHours(1));

        when(interviewRepository.findById(INTERVIEW_ID)).thenReturn(Optional.of(InterviewEntity.builder()
                .interviewId(INTERVIEW_ID).userId(7L).sessionId("b1c2d3").build()));
        when(recordingLoader.presign(anyString())).thenAnswer(i -> "https://signed/" + i.getArgument(0));
        when(aiServerClient.requestAudioAnalysis(any())).thenAnswer(i -> new AudioAnalysisAcceptedResponse(
                "job-" + i.<AudioAnalysisRequest>getArgument(0).getRequestId(),
                i.<AudioAnalysisRequest>getArgument(0).getRequestId(), "b1c2d3", "accepted"));

        when(analysisRepository.findAllByInterviewIdOrderByAnalysisIdAsc(INTERVIEW_ID)).thenAnswer(i -> List.copyOf(analyses));
        when(analysisRepository.findByRequestId(anyString())).thenAnswer(i -> analyses.stream()
                .filter(a -> a.getRequestId().equals(i.getArgument(0))).findFirst());
        when(analysisRepository.findByJobId(anyString())).thenAnswer(i -> analyses.stream()
                .filter(a -> i.getArgument(0).equals(a.getJobId())).findFirst());
        when(analysisRepository.save(any())).thenAnswer(i -> {
            AudioAnalysisEntity saved = i.getArgument(0);
            if (saved.getAnalysisId() == null) {
                ReflectionTestUtils.setField(saved, "analysisId", (long) analyses.size() + 1);
                ReflectionTestUtils.setField(saved, "createdAt", LocalDateTime.now());
                analyses.add(saved);
            }
            return saved;
        });
    }

    // ---- 요청 ----

    @Test
    void 답변_음성을_스펙_모양대로_실어_보내고_접수를_남긴다() {
        answerRecordings(loaded(301L, 101L, 201L, RecordingEndReason.TIMEOUT));

        service.requestForFinishedInterview(INTERVIEW_ID);

        AudioAnalysisRequest sent = sentRequests().getFirst();
        assertThat(sent.getRequestId()).startsWith("audio-");
        assertThat(sent.getSessionId()).isEqualTo("b1c2d3");
        assertThat(sent.getInterviewId()).isEqualTo("42");
        assertThat(sent.getUserId()).isEqualTo("7");
        assertThat(sent.getCallbackUrl()).isEqualTo("https://api.repit.test/api/analyses/audio/callback");

        AudioAnalysisRequest.Recording recording = sent.getRecordings().getFirst();
        assertThat(recording.getRecordingId()).isEqualTo("301");
        assertThat(recording.getQuestionId()).isEqualTo("101");
        assertThat(recording.getAnswerId()).isEqualTo("201");
        assertThat(recording.getFileUrl()).isEqualTo("https://signed/interview-recordings/42/301.mp3");
        assertThat(recording.getContentType()).isEqualTo("audio/mpeg");
        assertThat(recording.getFileSize()).isEqualTo(1024L);
        assertThat(recording.getUploadedAt()).isEqualTo(OffsetDateTime.parse("2026-09-21T07:56:31Z"));
        assertThat(recording.getEndReason()).isEqualTo("timeout");

        AudioAnalysisEntity accepted = analyses.getFirst();
        assertThat(accepted.getStatus()).isEqualTo(AudioAnalysisStatus.PENDING);
        assertThat(accepted.getRequestId()).isEqualTo(sent.getRequestId());
        assertThat(accepted.getJobId()).isEqualTo("job-" + sent.getRequestId());
        assertThat(accepted.getRecordingIds()).containsExactly(301L);
    }

    @Test
    void 웹이_종료_이유를_보내지_않았으면_unknown으로_보낸다() {
        answerRecordings(loaded(301L, 101L, 201L, null));

        service.requestForFinishedInterview(INTERVIEW_ID);

        assertThat(sentRequests().getFirst().getRecordings().getFirst().getEndReason()).isEqualTo("unknown");
    }

    /** 분석 서버는 한 요청에 1~12개만 받는다. 넘기면 요청 전체가 거부된다. */
    @Test
    void 녹음이_12개를_넘으면_나눠_보낸다() {
        answerRecordings(LongStream.rangeClosed(1, 13)
                .mapToObj(n -> loaded(300 + n, 100 + n, 200 + n, null))
                .toArray(AnswerRecording[]::new));

        service.requestForFinishedInterview(INTERVIEW_ID);

        List<AudioAnalysisRequest> sent = sentRequests();
        assertThat(sent).hasSize(2);
        assertThat(sent.get(0).getRecordings()).hasSize(12);
        assertThat(sent.get(1).getRecordings()).extracting(AudioAnalysisRequest.Recording::getRecordingId)
                .containsExactly("313");
        assertThat(sent.get(0).getRequestId()).isNotEqualTo(sent.get(1).getRequestId());
        assertThat(analyses).hasSize(2);
    }

    @Test
    void 음성이_없으면_요청하지_않는다() {
        answerRecordings();

        service.requestForFinishedInterview(INTERVIEW_ID);

        verify(aiServerClient, never()).requestAudioAnalysis(any());
    }

    /** 나눠 보내다 실패했으면 다시 시도할 때 앞서 접수된 녹음은 빼고 보낸다. 같은 답변이 두 번 분석되지 않는다. */
    @Test
    void 이미_맡긴_녹음은_다시_보내지_않는다() {
        answerRecordings(LongStream.rangeClosed(1, 13)
                .mapToObj(n -> loaded(300 + n, 100 + n, 200 + n, null))
                .toArray(AnswerRecording[]::new));
        doAnswer(i -> new AudioAnalysisAcceptedResponse("job-1", "r", "b1c2d3", "accepted"))
                .doThrow(new ExternalApiException("AI 오류", HttpStatus.INTERNAL_SERVER_ERROR, null))
                .doAnswer(i -> new AudioAnalysisAcceptedResponse("job-2", "r", "b1c2d3", "accepted"))
                .when(aiServerClient).requestAudioAnalysis(any());

        assertThatThrownBy(() -> service.requestForFinishedInterview(INTERVIEW_ID))
                .isInstanceOf(ExternalApiException.class);
        service.requestForFinishedInterview(INTERVIEW_ID);

        List<AudioAnalysisRequest> sent = sentRequests();
        assertThat(sent).hasSize(3);
        assertThat(sent.get(2).getRecordings()).extracting(AudioAnalysisRequest.Recording::getRecordingId)
                .containsExactly("313");
    }

    @Test
    void 이미_다_맡겼으면_요청하지_않는다() {
        answerRecordings(loaded(301L, 101L, 201L, null));
        analyses.add(analysis("audio-old", AudioAnalysisStatus.READY, List.of(301L), LocalDateTime.now()));

        service.requestForFinishedInterview(INTERVIEW_ID);

        verify(aiServerClient, never()).requestAudioAnalysis(any());
    }

    /**
     * 콜백 재전송이 모두 실패해도 결과는 분석 서버에 남는다. 작업을 직접 조회해 가져오지 않으면, 끝난 분석을
     * 버리고 같은 녹음을 다시 맡기게 된다.
     */
    @Test
    void 콜백을_못_받은_작업은_직접_조회해_결과를_가져온다() {
        answerRecordings(loaded(301L, 101L, 201L, null));
        AudioAnalysisEntity waiting = analysis("audio-old", AudioAnalysisStatus.PENDING, List.of(301L),
                LocalDateTime.now().minusMinutes(11));
        analyses.add(waiting);
        when(aiServerClient.getAudioAnalysisJob("job-audio-old")).thenReturn(new AudioAnalysisJobResponse(
                "job-audio-old", "succeeded",
                callback("audio-old", "partial", List.of(result("301", "partial", null)))));

        service.requestForFinishedInterview(INTERVIEW_ID);

        assertThat(waiting.getStatus()).isEqualTo(AudioAnalysisStatus.PARTIAL);
        assertThat(savedResults()).extracting(AudioAnalysisResultEntity::getRecordingId).containsExactly(301L);
        verify(aiServerClient, never()).requestAudioAnalysis(any());
    }

    /**
     * 조회가 잠깐 실패한 것을 "결과가 없다"로 보면 안 된다. 그렇게 닫으면 살아 있는 작업을 버린 채 같은 답변을
     * 다시 맡겨, 한 답변이 두 번 분석된다.
     */
    @Test
    void 작업_조회가_실패하면_닫지_않고_기다린다() {
        answerRecordings(loaded(301L, 101L, 201L, null));
        AudioAnalysisEntity waiting = analysis("audio-old", AudioAnalysisStatus.PENDING, List.of(301L),
                LocalDateTime.now().minusMinutes(11));
        analyses.add(waiting);
        when(aiServerClient.getAudioAnalysisJob("job-audio-old"))
                .thenThrow(new ExternalApiException("AI 응답 생성 중 오류가 발생했습니다.",
                        HttpStatus.INTERNAL_SERVER_ERROR, null));

        service.requestForFinishedInterview(INTERVIEW_ID);

        assertThat(waiting.getStatus()).isEqualTo(AudioAnalysisStatus.PENDING);
        verify(aiServerClient, never()).requestAudioAnalysis(any());
    }

    /** 아직 분석 중일 수 있다. 조회가 답했다는 것만으로 닫으면 마찬가지로 두 번 분석된다. */
    @Test
    void 결과가_아직_없으면_닫지_않고_기다린다() {
        answerRecordings(loaded(301L, 101L, 201L, null));
        AudioAnalysisEntity waiting = analysis("audio-old", AudioAnalysisStatus.PENDING, List.of(301L),
                LocalDateTime.now().minusMinutes(11));
        analyses.add(waiting);
        when(aiServerClient.getAudioAnalysisJob("job-audio-old"))
                .thenReturn(new AudioAnalysisJobResponse("job-audio-old", "running", null));

        service.requestForFinishedInterview(INTERVIEW_ID);

        assertThat(waiting.getStatus()).isEqualTo(AudioAnalysisStatus.PENDING);
        verify(aiServerClient, never()).requestAudioAnalysis(any());
    }

    /** 끝내 결과를 찾지 못하면 그 녹음이 영영 분석되지 않는다. 포기 시간이 지나면 닫아 다시 맡긴다. */
    @Test
    void 포기_시간까지_결과가_없으면_닫고_새_요청_id로_다시_보낸다() {
        answerRecordings(loaded(301L, 101L, 201L, null));
        AudioAnalysisEntity stale = analysis("audio-old", AudioAnalysisStatus.PENDING, List.of(301L),
                LocalDateTime.now().minusMinutes(61));
        analyses.add(stale);
        when(aiServerClient.getAudioAnalysisJob("job-audio-old"))
                .thenReturn(new AudioAnalysisJobResponse("job-audio-old", "running", null));

        service.requestForFinishedInterview(INTERVIEW_ID);

        assertThat(stale.getStatus()).isEqualTo(AudioAnalysisStatus.FAILED);
        List<AudioAnalysisRequest> sent = sentRequests();
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().getRequestId()).isNotEqualTo("audio-old");
    }

    @Test
    void 콜백을_기다리는_중인_녹음은_다시_보내지_않는다() {
        answerRecordings(loaded(301L, 101L, 201L, null));
        analyses.add(analysis("audio-old", AudioAnalysisStatus.PENDING, List.of(301L), LocalDateTime.now()));

        service.requestForFinishedInterview(INTERVIEW_ID);

        verify(aiServerClient, never()).requestAudioAnalysis(any());
    }

    /**
     * 보냈지만 202를 못 받은 요청은 접수됐는지 알 수 없다. 새 요청 id로 보내면 같은 답변이 두 번 분석되므로
     * 같은 요청 id로 보내 확인한다 — 분석 서버는 같은 id·내용이면 기존 작업을 돌려준다.
     */
    @Test
    void 접수를_확인하지_못한_요청은_같은_요청_id로_다시_보낸다() {
        answerRecordings(loaded(301L, 101L, 201L, null));
        doThrow(new ExternalApiException("AI 서버와 연결할 수 없습니다.", null, null))
                .doAnswer(i -> new AudioAnalysisAcceptedResponse("job-1",
                        i.<AudioAnalysisRequest>getArgument(0).getRequestId(), "b1c2d3", "accepted"))
                .when(aiServerClient).requestAudioAnalysis(any());

        assertThatThrownBy(() -> service.requestForFinishedInterview(INTERVIEW_ID))
                .isInstanceOf(ExternalApiException.class);
        // 접수를 확인하지 못했으니 기록은 열린 채로 남는다.
        assertThat(analyses).hasSize(1);
        assertThat(analyses.getFirst().getStatus()).isEqualTo(AudioAnalysisStatus.PENDING);
        assertThat(analyses.getFirst().getJobId()).isNull();

        service.requestForFinishedInterview(INTERVIEW_ID);

        List<AudioAnalysisRequest> sent = sentRequests();
        assertThat(sent).hasSize(2);
        assertThat(sent.get(1).getRequestId()).isEqualTo(sent.get(0).getRequestId());
        assertThat(analyses).hasSize(1);
        assertThat(analyses.getFirst().getJobId()).isEqualTo("job-1");
    }

    /**
     * 202를 못 받은 요청도 분석 서버는 받아 두었을 수 있다. 일찍 닫고 새 요청 id로 보내면 두 번 분석되므로,
     * 포기 시간까지는 같은 요청 id로 확인만 한다.
     */
    @Test
    void 접수를_확인하지_못한_요청은_포기_시간_전에는_닫지_않는다() {
        answerRecordings(loaded(301L, 101L, 201L, null));
        AudioAnalysisEntity unacknowledged = analysis("audio-old", null, AudioAnalysisStatus.PENDING, List.of(301L),
                LocalDateTime.now().minusMinutes(30));
        analyses.add(unacknowledged);

        service.requestForFinishedInterview(INTERVIEW_ID);

        assertThat(unacknowledged.getStatus()).isEqualTo(AudioAnalysisStatus.PENDING);
        List<AudioAnalysisRequest> sent = sentRequests();
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().getRequestId()).isEqualTo("audio-old");
    }

    @Test
    void 접수를_확인하지_못한_채_포기_시간이_지나면_닫고_새_요청_id로_보낸다() {
        answerRecordings(loaded(301L, 101L, 201L, null));
        AudioAnalysisEntity unacknowledged = analysis("audio-old", null, AudioAnalysisStatus.PENDING, List.of(301L),
                LocalDateTime.now().minusMinutes(61));
        analyses.add(unacknowledged);

        service.requestForFinishedInterview(INTERVIEW_ID);

        assertThat(unacknowledged.getStatus()).isEqualTo(AudioAnalysisStatus.FAILED);
        List<AudioAnalysisRequest> sent = sentRequests();
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().getRequestId()).isNotEqualTo("audio-old");
    }

    /** 분석 서버가 상태 코드로 답한 거절은 접수되지 않은 것이다. 닫지 않으면 그 녹음이 영영 분석되지 않는다. */
    @Test
    void 분석_서버가_거절한_요청은_닫아_그_녹음이_다시_나가게_한다() {
        answerRecordings(loaded(301L, 101L, 201L, null));
        doThrow(new ExternalApiException("AI 서버가 처리할 수 있는 형식의 데이터가 아닙니다.",
                HttpStatus.UNPROCESSABLE_ENTITY, null))
                .doAnswer(i -> new AudioAnalysisAcceptedResponse("job-2",
                        i.<AudioAnalysisRequest>getArgument(0).getRequestId(), "b1c2d3", "accepted"))
                .when(aiServerClient).requestAudioAnalysis(any());

        assertThatThrownBy(() -> service.requestForFinishedInterview(INTERVIEW_ID))
                .isInstanceOf(ExternalApiException.class);
        assertThat(analyses.getFirst().getStatus()).isEqualTo(AudioAnalysisStatus.FAILED);

        service.requestForFinishedInterview(INTERVIEW_ID);

        List<AudioAnalysisRequest> sent = sentRequests();
        assertThat(sent).hasSize(2);
        assertThat(sent.get(1).getRequestId()).isNotEqualTo(sent.get(0).getRequestId());
    }

    /** 같은 요청 id로 다른 내용을 보냈다는 뜻이다. 그 id로는 무엇을 해도 받아주지 않으므로 새 id로 보낸다. */
    @Test
    void 같은_요청_id를_거절당하면_새_요청_id로_한_번_다시_보낸다() {
        answerRecordings(loaded(301L, 101L, 201L, null));
        doThrow(new ExternalApiException("AI 서버 요청이 올바르지 않습니다. (409)", HttpStatus.CONFLICT, null))
                .doAnswer(i -> new AudioAnalysisAcceptedResponse("job-2",
                        i.<AudioAnalysisRequest>getArgument(0).getRequestId(), "b1c2d3", "accepted"))
                .when(aiServerClient).requestAudioAnalysis(any());

        service.requestForFinishedInterview(INTERVIEW_ID);

        List<AudioAnalysisRequest> sent = sentRequests();
        assertThat(sent).hasSize(2);
        assertThat(sent.get(1).getRequestId()).isNotEqualTo(sent.get(0).getRequestId());
        assertThat(analyses).hasSize(2);
        assertThat(analyses.get(0).getStatus()).isEqualTo(AudioAnalysisStatus.FAILED);
        assertThat(analyses.get(1).getStatus()).isEqualTo(AudioAnalysisStatus.PENDING);
        assertThat(analyses.get(1).getJobId()).isEqualTo("job-2");
    }

    // ---- 명세가 거는 제약 ----

    /** 하나라도 상한을 넘으면 함께 실린 나머지 녹음까지 422로 거절된다. */
    @Test
    void 크기_상한을_넘는_녹음은_빼고_보낸다() {
        answerRecordings(loaded(301L, 101L, 201L, null), sized(302L, 102L, 202L, 100_000_001L));

        service.requestForFinishedInterview(INTERVIEW_ID);

        assertThat(sentRequests().getFirst().getRecordings())
                .extracting(AudioAnalysisRequest.Recording::getRecordingId).containsExactly("301");
    }

    @Test
    void 크기가_0인_녹음도_빼고_보낸다() {
        answerRecordings(sized(301L, 101L, 201L, 0L), loaded(302L, 102L, 202L, null));

        service.requestForFinishedInterview(INTERVIEW_ID);

        assertThat(sentRequests().getFirst().getRecordings())
                .extracting(AudioAnalysisRequest.Recording::getRecordingId).containsExactly("302");
    }

    /** audio/ 로 시작하고 파라미터가 없어야 한다. 아니면 요청 전체가 422다. */
    @Test
    void 형식_이름은_파라미터를_떼고_음성으로_보낸다() {
        answerRecordings(typed(301L, "audio/webm;codecs=opus"), typed(302L, "video/mp4"));

        service.requestForFinishedInterview(INTERVIEW_ID);

        assertThat(sentRequests().getFirst().getRecordings())
                .extracting(AudioAnalysisRequest.Recording::getContentType)
                .containsExactly("audio/webm", "audio/mp4");
    }

    /** 세션 id는 요청 id와 함께 중복 판단 키다. 비워 보내면 요청 전체가 거절된다. */
    @Test
    void 채팅_세션이_없는_면접은_보내지_않는다() {
        when(interviewRepository.findById(INTERVIEW_ID)).thenReturn(Optional.of(InterviewEntity.builder()
                .interviewId(INTERVIEW_ID).userId(7L).build()));
        answerRecordings(loaded(301L, 101L, 201L, null));

        assertThatThrownBy(() -> service.requestForFinishedInterview(INTERVIEW_ID))
                .isInstanceOf(BusinessException.class);
        verify(aiServerClient, never()).requestAudioAnalysis(any());
    }

    @Test
    void 콜백_주소가_https가_아니면_보내지_않는다() {
        ReflectionTestUtils.setField(service, "callbackBaseUrl", "http://api.repit.test");
        answerRecordings(loaded(301L, 101L, 201L, null));

        assertThatThrownBy(() -> service.requestForFinishedInterview(INTERVIEW_ID))
                .isInstanceOf(BusinessException.class);
        verify(aiServerClient, never()).requestAudioAnalysis(any());
    }

    /** 한 요청 안에서 답변 id가 겹치면 거절된다. */
    @Test
    void 같은_답변을_가리키는_녹음이_둘이면_뒤엣것을_뺀다() {
        answerRecordings(loaded(301L, 101L, 201L, null), loaded(302L, 102L, 201L, null));

        service.requestForFinishedInterview(INTERVIEW_ID);

        assertThat(sentRequests().getFirst().getRecordings())
                .extracting(AudioAnalysisRequest.Recording::getRecordingId).containsExactly("301");
    }

    // ---- 콜백 ----

    @Test
    void 콜백의_전체_상태와_녹음별_결과를_남긴다() {
        AudioAnalysisEntity pending = analysis("audio-request-001", AudioAnalysisStatus.PENDING, List.of(301L),
                LocalDateTime.now());
        analyses.add(pending);

        service.handleCallback(callback("audio-request-001", "partial", List.of(result("301", "partial", "boom"))));

        assertThat(pending.getStatus()).isEqualTo(AudioAnalysisStatus.PARTIAL);
        // 접수 때 받아 둔 작업 id를 콜백 값으로 덮지 않는다.
        assertThat(pending.getJobId()).isEqualTo("job-audio-request-001");

        AudioAnalysisResultEntity row = savedResults().getFirst();
        assertThat(row.getAnalysisId()).isEqualTo(pending.getAnalysisId());
        assertThat(row.getRecordingId()).isEqualTo(301L);
        assertThat(row.getQuestionId()).isEqualTo(101L);
        assertThat(row.getAnswerId()).isEqualTo(201L);
        assertThat(row.getStatus()).isEqualTo("PARTIAL");
        assertThat(row.getDurationMs()).isEqualTo(120000L);
        assertThat(row.getTiming()).containsEntry("status", "ready");
        assertThat(row.getFluency()).containsEntry("status", "partial");
        assertThat(row.getError()).isEqualTo("boom");
    }

    /** 재전송된 콜백으로 결과가 두 벌 쌓이면 안 된다. */
    @Test
    void 같은_콜백이_다시_와도_결과를_지우고_다시_넣는다() {
        analyses.add(analysis("audio-request-001", AudioAnalysisStatus.PENDING, List.of(301L), LocalDateTime.now()));

        service.handleCallback(callback("audio-request-001", "ready", List.of(result("301", "ready", null))));
        service.handleCallback(callback("audio-request-001", "ready", List.of(result("301", "ready", null))));

        verify(resultRepository, times(2)).deleteAllByAnalysisId(1L);
    }

    /** 분기는 code와 retryable로 한다. message는 코드마다 같은 문구라 쓸 수 없다. */
    @Test
    void 실패_사유는_전문과_함께_코드와_재시도_가능_여부를_남긴다() {
        analyses.add(analysis("audio-request-001", AudioAnalysisStatus.PENDING, List.of(301L), LocalDateTime.now()));
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", "SOURCE_ACCESS_DENIED_OR_EXPIRED");
        error.put("message", "음성 분석의 일부 작업을 완료하지 못했습니다.");
        error.put("retryable", true);

        service.handleCallback(callback("audio-request-001", "unavailable",
                List.of(result("301", "unavailable", error))));

        AudioAnalysisResultEntity row = savedResults().getFirst();
        assertThat(row.getErrorCode()).isEqualTo("SOURCE_ACCESS_DENIED_OR_EXPIRED");
        assertThat(row.getErrorRetryable()).isTrue();
        assertThat(row.getError()).contains("SOURCE_ACCESS_DENIED_OR_EXPIRED").contains("retryable");
    }

    /** 접수 응답을 못 받았어도 콜백은 온다. 여기서 버리면 분석 서버가 결과를 폐기한다. */
    @Test
    void 접수_기록이_없는_콜백은_면접으로_되짚어_받아낸다() {
        service.handleCallback(callback("audio-request-001", "ready", List.of(result("301", "ready", null))));

        assertThat(analyses).hasSize(1);
        AudioAnalysisEntity created = analyses.getFirst();
        assertThat(created.getInterviewId()).isEqualTo(INTERVIEW_ID);
        assertThat(created.getUserId()).isEqualTo(7L);
        assertThat(created.getRequestId()).isEqualTo("audio-request-001");
        assertThat(created.getStatus()).isEqualTo(AudioAnalysisStatus.READY);
        assertThat(created.getRecordingIds()).containsExactly(301L);
    }

    @Test
    void 알_수_없는_면접의_콜백은_남기지_않는다() {
        when(interviewRepository.findById(INTERVIEW_ID)).thenReturn(Optional.empty());

        service.handleCallback(callback("audio-request-001", "ready", List.of()));

        assertThat(analyses).isEmpty();
        verify(resultRepository, never()).saveAll(anyList());
    }

    @Test
    void 알_수_없는_상태면_받을_결과가_없는_것으로_본다() {
        AudioAnalysisEntity pending = analysis("audio-request-001", AudioAnalysisStatus.PENDING, List.of(),
                LocalDateTime.now());
        analyses.add(pending);

        service.handleCallback(callback("audio-request-001", "weird", List.of()));

        assertThat(pending.getStatus()).isEqualTo(AudioAnalysisStatus.UNAVAILABLE);
    }

    private void answerRecordings(AnswerRecording... loaded) {
        when(recordingLoader.load(eq(INTERVIEW_ID), anyList(), anyList())).thenReturn(List.of(loaded));
    }

    private List<AudioAnalysisRequest> sentRequests() {
        ArgumentCaptor<AudioAnalysisRequest> captor = ArgumentCaptor.forClass(AudioAnalysisRequest.class);
        verify(aiServerClient, atLeastOnce()).requestAudioAnalysis(captor.capture());
        return captor.getAllValues();
    }

    @SuppressWarnings("unchecked")
    private List<AudioAnalysisResultEntity> savedResults() {
        ArgumentCaptor<List<AudioAnalysisResultEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(resultRepository, atLeastOnce()).saveAll(captor.capture());
        return captor.getValue();
    }

    /** 접수까지 끝난 요청. jobId가 있으면 결과를 기다리는 중이다. */
    private AudioAnalysisEntity analysis(String requestId, AudioAnalysisStatus status, List<Long> recordingIds,
                                         LocalDateTime createdAt) {
        return analysis(requestId, "job-" + requestId, status, recordingIds, createdAt);
    }

    private AudioAnalysisEntity analysis(String requestId, String jobId, AudioAnalysisStatus status,
                                         List<Long> recordingIds, LocalDateTime createdAt) {
        return AudioAnalysisEntity.builder()
                .analysisId((long) analyses.size() + 1)
                .interviewId(INTERVIEW_ID).userId(7L).sessionId("b1c2d3")
                .requestId(requestId).jobId(jobId).status(status).recordingIds(recordingIds).createdAt(createdAt)
                .build();
    }

    private static AudioAnalysisCallbackRequest callback(String requestId, String status,
                                                         List<AudioAnalysisCallbackRequest.Result> results) {
        return new AudioAnalysisCallbackRequest("audio-job-001", requestId, "b1c2d3", "42", "7", status, results);
    }

    private static AudioAnalysisCallbackRequest.Result result(String recordingId, String status, Object error) {
        return new AudioAnalysisCallbackRequest.Result(recordingId, "101", "201", status, 120000L,
                Map.of("status", "ready", "data", Map.of("wpm", 132)),
                Map.of("status", "partial", "data", Map.of()),
                error);
    }

    private static AnswerRecording sized(long recordingId, long questionId, long answerId, long fileSize) {
        AnswerRecording base = loaded(recordingId, questionId, answerId, null);
        ReflectionTestUtils.setField(base.recording(), "fileSize", fileSize);
        return base;
    }

    private static AnswerRecording typed(long recordingId, String contentType) {
        AnswerRecording base = loaded(recordingId, recordingId - 200, recordingId - 100, null);
        ReflectionTestUtils.setField(base.recording(), "contentType", contentType);
        return base;
    }

    private static AnswerRecording loaded(long recordingId, long questionId, long answerId, RecordingEndReason endReason) {
        InterviewRecordingEntity recording = InterviewRecordingEntity.builder()
                .recordingId(recordingId).interviewId(INTERVIEW_ID).userId(7L)
                .kind(RecordingKind.ANSWER).chatQuestionId(questionId).endReason(endReason).contentType("audio/mpeg")
                .s3Key("interview-recordings/42/" + recordingId + ".mp3").fileSize(1024L)
                .createdAt(LocalDateTime.parse("2026-09-21T07:56:31"))
                .build();
        return new AnswerRecording(recording, questionId, answerId);
    }
}
