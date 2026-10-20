package repit.repit_api_server.domain.userdata.analysis.service;

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
import repit.repit_api_server.domain.userdata.analysis.dto.response.VideoAnalysisAcceptedResponse;
import repit.repit_api_server.domain.userdata.analysis.dto.response.VideoAnalysisJobResponse;
import repit.repit_api_server.domain.userdata.analysis.dto.response.VideoAnalysisResponse;
import repit.repit_api_server.domain.userdata.analysis.entity.VideoAnalysisEntity;
import repit.repit_api_server.domain.userdata.analysis.entity.enums.VideoAnalysisRequestedBy;
import repit.repit_api_server.domain.userdata.analysis.entity.enums.VideoAnalysisStatus;
import repit.repit_api_server.domain.userdata.analysis.repository.VideoAnalysisRepository;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.AnswerMode;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.recording.entity.InterviewRecordingEntity;
import repit.repit_api_server.domain.userdata.recording.entity.enums.RecordingKind;
import repit.repit_api_server.domain.userdata.recording.repository.InterviewRecordingRepository;
import repit.repit_api_server.global.client.AiServerClient;
import repit.repit_api_server.global.exception.BusinessException;
import repit.repit_api_server.global.exception.ExternalApiException;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 영상 분석을 분석 서버와 맞춘 규칙대로 보내고, 다시 보내고, 조회하고, 닫는지.
 *
 * <p>규칙을 어기면 같은 영상이 두 번 분석되거나(409·조회 장애에 새 요청), 끝난 분석이 영영 "분석 중"으로 남는다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VideoAnalysisServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long INTERVIEW_ID = 42L;
    private static final Long RECORDING_ID = 301L;
    private static final Long ANALYSIS_ID = 9L;

    @Mock
    private VideoAnalysisRepository analysisRepository;
    @Mock
    private InterviewRepository interviewRepository;
    @Mock
    private InterviewRecordingRepository recordingRepository;
    @Mock
    private VideoAnalysisCallbackHandler callbackHandler;
    @Mock
    private AiServerClient aiServerClient;
    @Mock
    private PlatformTransactionManager transactionManager;

    private S3Presigner presigner;
    private VideoAnalysisService service;

    /** 영상의 요청 기록. 오래된 것부터. */
    private final List<VideoAnalysisEntity> analyses = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // 서명은 네트워크 없이 로컬에서 만들어진다.
        presigner = S3Presigner.builder()
                .region(Region.AP_NORTHEAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
        // 잠금·트랜잭션은 영속성 테스트가 실제 DB로 본다. 여기서는 트랜잭션 경계만 흉내 낸다.
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new VideoAnalysisService(analysisRepository, interviewRepository, recordingRepository,
                callbackHandler, aiServerClient, presigner, JsonMapper.builder().build(),
                new TransactionTemplate(transactionManager));
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "internalAuthToken", "secret");
        ReflectionTestUtils.setField(service, "videoApiToken", "video-secret");
        // 설정 문자열만 둔다. 기준 시각이 필요한 테스트는 따로 정한다.
        ReflectionTestUtils.setField(service, "analyzeUploadedAfterSetting", "2026-10-20T09:00:00+09:00");
        ReflectionTestUtils.setField(service, "callbackBaseUrl", "https://api.repit.test");
        ReflectionTestUtils.setField(service, "bucketName", "repit-bucket");

        when(interviewRepository.findById(INTERVIEW_ID)).thenReturn(Optional.of(interview("b1c2d3")));
        when(interviewRepository.lockById(INTERVIEW_ID)).thenAnswer(i -> interviewRepository.findById(INTERVIEW_ID));
        when(recordingRepository.findFirstByInterviewIdAndKindOrderByRecordingIdDesc(INTERVIEW_ID, RecordingKind.FULL_INTERVIEW))
                .thenAnswer(i -> Optional.of(video(LocalDateTime.now().minusDays(1))));
        when(analysisRepository.findAllByRecordingIdOrderByAnalysisIdAsc(RECORDING_ID)).thenAnswer(i -> List.copyOf(analyses));
        when(analysisRepository.save(any())).thenAnswer(i -> {
            VideoAnalysisEntity saved = i.getArgument(0);
            ReflectionTestUtils.setField(saved, "analysisId", (long) analyses.size() + 100);
            analyses.add(saved);
            return saved;
        });
    }

    @AfterEach
    void tearDown() {
        presigner.close();
    }

    // ---- 요청 자리 ----

    @Test
    void 최종_영상을_명세_모양의_본문으로_남기고_스윕이_곧바로_보내게_둔다() {
        VideoAnalysisEntity opened = service.open(video(LocalDateTime.of(2026, 9, 30, 7, 56, 31)), "chain-a",
                VideoAnalysisRequestedBy.AUTO);

        assertThat(opened.getStatus()).isEqualTo(VideoAnalysisStatus.PENDING);
        assertThat(opened.getNextCheckAt()).isNotNull();
        assertThat(opened.getRequestId()).startsWith("video-");
        assertThat(opened.getChainId()).isEqualTo("chain-a");

        Map<String, Object> payload = opened.getRequestPayload();
        assertThat(payload).containsEntry("requestId", opened.getRequestId())
                .containsEntry("sessionId", "b1c2d3")
                .containsEntry("interviewId", "42")
                .containsEntry("userId", "7")
                .containsEntry("callbackUrl", "https://api.repit.test/api/analyses/video/callback");
        @SuppressWarnings("unchecked")
        Map<String, Object> video = (Map<String, Object>) payload.get("video");
        assertThat(video).containsEntry("videoId", "301")
                .containsEntry("contentType", "video/webm")
                .containsEntry("uploadedAt", "2026-09-30T07:56:31Z");
        assertThat(((Number) video.get("fileSize")).longValue()).isEqualTo(500_000_000L);
        assertThat((String) video.get("fileUrl"))
                .startsWith("https://repit-bucket.s3.ap-northeast-2.amazonaws.com/interview-recordings/42/v.webm")
                .contains("X-Amz-Signature=");
        // 보내는 일은 스윕 한 곳에서만 한다.
        verify(aiServerClient, never()).requestVideoAnalysis(any());
    }

    @Test
    void 보낼_수_없는_영상은_보내지_않고_재분석도_막힌_실패로_남긴다() {
        when(interviewRepository.findById(INTERVIEW_ID)).thenReturn(Optional.of(interview(" ")));
        VideoAnalysisEntity noSession = service.open(video(LocalDateTime.now()), "chain-a", VideoAnalysisRequestedBy.AUTO);

        when(interviewRepository.findById(INTERVIEW_ID)).thenReturn(Optional.of(interview("b1c2d3")));
        InterviewRecordingEntity tooLarge = InterviewRecordingEntity.builder()
                .recordingId(RECORDING_ID).interviewId(INTERVIEW_ID).userId(USER_ID).kind(RecordingKind.FULL_INTERVIEW)
                .contentType("video/mp4").s3Key("k").fileSize(1_000_000_001L).createdAt(LocalDateTime.now()).build();
        VideoAnalysisEntity limit = service.open(tooLarge, "chain-b", VideoAnalysisRequestedBy.AUTO);

        InterviewRecordingEntity ogg = InterviewRecordingEntity.builder()
                .recordingId(RECORDING_ID).interviewId(INTERVIEW_ID).userId(USER_ID).kind(RecordingKind.FULL_INTERVIEW)
                .contentType("video/ogg").s3Key("k").fileSize(10L).createdAt(LocalDateTime.now()).build();
        VideoAnalysisEntity format = service.open(ogg, "chain-c", VideoAnalysisRequestedBy.AUTO);

        assertThat(List.of(noSession, limit, format)).allSatisfy(closed -> {
            assertThat(closed.getStatus()).isEqualTo(VideoAnalysisStatus.FAILED);
            assertThat(closed.getErrorRetryable()).isFalse();
            assertThat(closed.getRequestPayload()).isNull();
        });
        assertThat(noSession.getErrorCode()).isEqualTo("SESSION_MISSING");
        assertThat(limit.getErrorCode()).isEqualTo("VIDEO_LIMIT_EXCEEDED");
        assertThat(format.getErrorCode()).isEqualTo("VIDEO_FORMAT_UNSUPPORTED");
    }

    // ---- 보내기 ----

    @Test
    void 접수되면_작업_id를_적고_10분_뒤부터_조회한다() {
        VideoAnalysisEntity pending = pending(null, 0, LocalDateTime.now());
        when(aiServerClient.requestVideoAnalysis(any())).thenReturn(new VideoAnalysisAcceptedResponse("job-1"));

        service.check(pending);

        verify(aiServerClient).requestVideoAnalysis(pending.getRequestPayload());
        verify(analysisRepository).countSend(eq(ANALYSIS_ID), any());
        ArgumentCaptor<LocalDateTime> nextCheck = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(analysisRepository).acknowledge(eq(ANALYSIS_ID), eq("job-1"), nextCheck.capture(), any());
        assertThat(nextCheck.getValue()).isAfter(LocalDateTime.now().plusMinutes(9));
    }

    @Test
    void 접수를_확인하지_못하면_저장한_본문_그대로_다시_보낼_자리에_둔다() {
        VideoAnalysisEntity pending = pending(null, 1, LocalDateTime.now().minusMinutes(1));
        when(aiServerClient.requestVideoAnalysis(any())).thenThrow(new ExternalApiException("timeout", null, null));

        service.check(pending);

        // 새 요청 id를 만들지 않는다. 다음 확인 때 같은 행의 같은 본문이 나간다.
        verify(aiServerClient).requestVideoAnalysis(pending.getRequestPayload());
        verify(analysisRepository).postpone(eq(ANALYSIS_ID), any(), anyString(), any());
        verify(analysisRepository, never()).fail(any(), any(), any(), any(), any());
        verify(analysisRepository, never()).save(any());
    }

    @Test
    void 같은_요청_id로_5번_보냈으면_새_요청_없이_닫는다() {
        service.check(pending(null, 5, LocalDateTime.now().minusMinutes(10)));

        verify(aiServerClient, never()).requestVideoAnalysis(any());
        verify(analysisRepository).fail(eq(ANALYSIS_ID), eq("NOT_ACKNOWLEDGED"), isNull(), anyString(), any());
        verify(analysisRepository, never()).save(any());
    }

    @Test
    void 처음_보낸_지_1시간이_지나면_더_보내지_않는다() {
        service.check(pending(null, 1, LocalDateTime.now().minusMinutes(61)));

        verify(aiServerClient, never()).requestVideoAnalysis(any());
        verify(analysisRepository).fail(eq(ANALYSIS_ID), eq("NOT_ACKNOWLEDGED"), isNull(), anyString(), any());
    }

    @Test
    void 다섯_번째_전송도_실패하면_닫는다() {
        when(aiServerClient.requestVideoAnalysis(any())).thenThrow(new ExternalApiException("503", HttpStatus.SERVICE_UNAVAILABLE, null));

        service.check(pending(null, 4, LocalDateTime.now().minusMinutes(5)));

        verify(aiServerClient).requestVideoAnalysis(any());
        verify(analysisRepository).fail(eq(ANALYSIS_ID), eq("NOT_ACKNOWLEDGED"), isNull(), anyString(), any());
    }

    @Test
    void 충돌_409면_닫고_새_요청을_만들지_않는다() {
        when(aiServerClient.requestVideoAnalysis(any())).thenThrow(new ExternalApiException("conflict", HttpStatus.CONFLICT, null));

        service.check(pending(null, 0, LocalDateTime.now()));

        verify(analysisRepository).fail(eq(ANALYSIS_ID), eq("CONFLICT"), isNull(), anyString(), any());
        verify(analysisRepository, never()).save(any());
    }

    // ---- 조회 ----

    @Test
    void 작업이_처리_중이면_새_분석_없이_기다린다() {
        when(aiServerClient.getVideoAnalysisJob("job-1")).thenReturn(new VideoAnalysisJobResponse("job-1", "processing", null, null));

        service.check(pending("job-1", 1, LocalDateTime.now().minusMinutes(30)));

        verify(analysisRepository).postpone(eq(ANALYSIS_ID), any(), anyString(), any());
        verify(analysisRepository, never()).fail(any(), any(), any(), any(), any());
        verify(aiServerClient, never()).requestVideoAnalysis(any());
    }

    @Test
    void 처리_중인_채로_6시간이_지나면_서비스상_실패로_닫되_자동_재분석_대상은_아니다() {
        when(aiServerClient.getVideoAnalysisJob("job-1")).thenReturn(new VideoAnalysisJobResponse("job-1", "processing", null, null));

        service.check(pending("job-1", 1, LocalDateTime.now().minusHours(6).minusMinutes(1)));

        verify(analysisRepository).fail(eq(ANALYSIS_ID), eq("PROCESSING_OVERDUE"), isNull(), anyString(), any());
        verify(aiServerClient, never()).requestVideoAnalysis(any());
    }

    @Test
    void 조회가_실패하면_아무것도_판단하지_않고_다시_조회한다() {
        when(aiServerClient.getVideoAnalysisJob("job-1"))
                .thenThrow(new ExternalApiException("down", HttpStatus.SERVICE_UNAVAILABLE, null));

        service.check(pending("job-1", 1, LocalDateTime.now().minusMinutes(30)));

        verify(analysisRepository).postpone(eq(ANALYSIS_ID), any(), anyString(), any());
        verify(analysisRepository, never()).fail(any(), any(), any(), any(), any());
        verify(aiServerClient, never()).requestVideoAnalysis(any());
    }

    @Test
    void 작업이_없으면_같은_요청_id와_본문으로_다시_접수한다() {
        VideoAnalysisEntity pending = pending("job-1", 1, LocalDateTime.now().minusMinutes(15));
        when(aiServerClient.getVideoAnalysisJob("job-1")).thenThrow(new ExternalApiException("gone", HttpStatus.NOT_FOUND, null));
        when(aiServerClient.requestVideoAnalysis(any())).thenReturn(new VideoAnalysisAcceptedResponse("job-2"));

        service.check(pending);

        verify(aiServerClient).requestVideoAnalysis(pending.getRequestPayload());
        verify(analysisRepository).acknowledge(eq(ANALYSIS_ID), eq("job-2"), any(), any());
        verify(analysisRepository, never()).save(any());
    }

    @Test
    void 작업이_없는데_다시_보낼_기간이_지났으면_자동_재분석으로_넘긴다() {
        when(aiServerClient.getVideoAnalysisJob("job-1")).thenThrow(new ExternalApiException("gone", HttpStatus.NOT_FOUND, null));

        service.check(pending("job-1", 1, LocalDateTime.now().minusHours(2)));

        verify(aiServerClient, never()).requestVideoAnalysis(any());
        verify(analysisRepository).fail(eq(ANALYSIS_ID), eq("JOB_NOT_FOUND"), eq(true), anyString(), any());
    }

    @Test
    void 보관_기간이_지나_지운_작업은_닫는다() {
        when(aiServerClient.getVideoAnalysisJob("job-1")).thenThrow(new ExternalApiException("gone", HttpStatus.GONE, null));

        service.check(pending("job-1", 1, LocalDateTime.now().minusMinutes(30)));

        verify(analysisRepository).fail(eq(ANALYSIS_ID), eq("RESULT_EXPIRED"), isNull(), anyString(), any());
    }

    @Test
    void 끝난_작업의_결과는_콜백과_같은_검증을_거쳐_저장하고_통과하지_못하면_닫는다() {
        Map<String, Object> result = Map.of("status", "ready");
        when(aiServerClient.getVideoAnalysisJob("job-1")).thenReturn(new VideoAnalysisJobResponse("job-1", "completed", result, null));
        when(callbackHandler.applyRecovered(ANALYSIS_ID, result)).thenReturn(VideoAnalysisCallbackHandler.Outcome.APPLIED);

        service.check(pending("job-1", 1, LocalDateTime.now().minusMinutes(30)));
        verify(analysisRepository, never()).fail(any(), any(), any(), any(), any());

        when(callbackHandler.applyRecovered(ANALYSIS_ID, result)).thenReturn(VideoAnalysisCallbackHandler.Outcome.INVALID);
        service.check(pending("job-1", 1, LocalDateTime.now().minusMinutes(30)));
        verify(analysisRepository).fail(eq(ANALYSIS_ID), eq("INVALID_RESULT"), isNull(), anyString(), any());
    }

    @Test
    void 작업_실패는_분석_서버가_알린_코드와_재시도_가능_여부로_닫는다() {
        when(aiServerClient.getVideoAnalysisJob("job-1")).thenReturn(new VideoAnalysisJobResponse("job-1", "failed", null,
                Map.of("code", "EXTERNAL_SERVICE_UNAVAILABLE", "message", "잠시 후 다시", "retryable", true)));

        service.check(pending("job-1", 1, LocalDateTime.now().minusMinutes(30)));

        verify(analysisRepository).fail(eq(ANALYSIS_ID), eq("EXTERNAL_SERVICE_UNAVAILABLE"), eq(true), eq("잠시 후 다시"), any());
    }

    // ---- 화면과 재분석 ----

    @Test
    void 상태는_최신_요청을_따르고_결과는_가장_나중의_완료_결과를_보여_준다() {
        analyses.add(done(1L, "chain-a", VideoAnalysisRequestedBy.AUTO, VideoAnalysisStatus.PARTIAL, null, Map.of("v", "first")));
        analyses.add(done(2L, "chain-b", VideoAnalysisRequestedBy.USER, VideoAnalysisStatus.FAILED, null, null));

        VideoAnalysisResponse failed = service.get(USER_ID, INTERVIEW_ID);
        assertThat(failed.status()).isEqualTo(VideoAnalysisResponse.Status.FAILED);
        assertThat(failed.result()).containsEntry("v", "first");
        assertThat(failed.canRetry()).isTrue();

        analyses.add(done(3L, "chain-c", VideoAnalysisRequestedBy.USER, VideoAnalysisStatus.PENDING, null, null));
        VideoAnalysisResponse analyzing = service.get(USER_ID, INTERVIEW_ID);
        assertThat(analyzing.status()).isEqualTo(VideoAnalysisResponse.Status.ANALYZING);
        assertThat(analyzing.result()).containsEntry("v", "first");
        assertThat(analyzing.canRetry()).isFalse();
    }

    @Test
    void 자동_재분석이_남은_실패는_분석_중으로_보이고_재분석을_받지_않는다() {
        analyses.add(done(1L, "chain-a", VideoAnalysisRequestedBy.AUTO, VideoAnalysisStatus.FAILED, true, null));

        assertThat(service.get(USER_ID, INTERVIEW_ID).status()).isEqualTo(VideoAnalysisResponse.Status.ANALYZING);
        assertThatThrownBy(() -> service.retry(USER_ID, INTERVIEW_ID)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("자동으로");
    }

    @Test
    void 자동_재분석_한도를_다_쓴_묶음은_실패로_보이고_사용자_재분석을_받는다() {
        for (long id = 1; id <= VideoAnalysisService.MAX_JOBS_PER_CHAIN; id++) {
            analyses.add(done(id, "chain-a", VideoAnalysisRequestedBy.AUTO, VideoAnalysisStatus.FAILED, true, null));
        }

        VideoAnalysisResponse response = service.get(USER_ID, INTERVIEW_ID);

        assertThat(response.status()).isEqualTo(VideoAnalysisResponse.Status.FAILED);
        assertThat(response.canRetry()).isTrue();
    }

    @Test
    void 재분석은_결과_없이_끝났거나_일부만_나온_요청에만_받는다() {
        InterviewRecordingEntity video = video(LocalDateTime.now().minusDays(1));
        LocalDateTime now = LocalDateTime.now();

        assertThat(service.retryBlockedReason(video, List.of(
                done(1L, "a", VideoAnalysisRequestedBy.AUTO, VideoAnalysisStatus.READY, null, Map.of())), now))
                .contains("마쳤");
        assertThat(service.retryBlockedReason(video, List.of(
                done(1L, "a", VideoAnalysisRequestedBy.AUTO, VideoAnalysisStatus.PENDING, null, null)), now))
                .contains("분석 중");
        assertThat(service.retryBlockedReason(video, List.of(
                done(1L, "a", VideoAnalysisRequestedBy.AUTO, VideoAnalysisStatus.UNAVAILABLE, false, Map.of())), now))
                .contains("새로 올려");
        // 이 서버가 닫은 실패(409·접수 미확인·처리 상한 초과)는 사용자가 다시 요청할 수 있다.
        assertThat(service.retryBlockedReason(video, List.of(
                done(1L, "a", VideoAnalysisRequestedBy.AUTO, VideoAnalysisStatus.FAILED, null, null)), now))
                .isNull();
        assertThat(service.retryBlockedReason(video, List.of(
                done(1L, "a", VideoAnalysisRequestedBy.AUTO, VideoAnalysisStatus.PARTIAL, null, Map.of())), now))
                .isNull();
    }

    @Test
    void 사용자_재분석은_영상당_2번까지다() {
        InterviewRecordingEntity video = video(LocalDateTime.now().minusDays(1));
        List<VideoAnalysisEntity> twice = List.of(
                done(1L, "a", VideoAnalysisRequestedBy.AUTO, VideoAnalysisStatus.FAILED, null, null),
                done(2L, "b", VideoAnalysisRequestedBy.USER, VideoAnalysisStatus.FAILED, null, null),
                // 사용자 재분석 뒤의 자동 재분석은 같은 묶음이라 따로 세지 않는다.
                done(3L, "b", VideoAnalysisRequestedBy.USER, VideoAnalysisStatus.FAILED, null, null),
                done(4L, "c", VideoAnalysisRequestedBy.USER, VideoAnalysisStatus.FAILED, null, null));

        assertThat(service.retryBlockedReason(video, twice.subList(0, 3), LocalDateTime.now())).isNull();
        assertThat(service.retryBlockedReason(video, twice, LocalDateTime.now())).contains("2번");
    }

    @Test
    void 보관_기간은_재분석_시각이_아니라_영상이_올라온_시각으로_센다() {
        InterviewRecordingEntity old = video(LocalDateTime.now().minusDays(89));
        // 재분석 요청이 어제 있었어도 영상은 89일 전에 올라왔다.
        VideoAnalysisEntity recent = done(1L, "a", VideoAnalysisRequestedBy.USER, VideoAnalysisStatus.FAILED, null, null);
        ReflectionTestUtils.setField(recent, "createdAt", LocalDateTime.now().minusDays(1));

        assertThat(service.retryBlockedReason(old, List.of(recent), LocalDateTime.now())).contains("90일");
    }

    @Test
    void 재분석은_새_묶음을_열어_사용자_요청으로_남긴다() {
        analyses.add(done(1L, "chain-a", VideoAnalysisRequestedBy.AUTO, VideoAnalysisStatus.UNAVAILABLE, null, Map.of()));

        VideoAnalysisResponse response = service.retry(USER_ID, INTERVIEW_ID);

        VideoAnalysisEntity opened = analyses.getLast();
        assertThat(opened.getRequestedBy()).isEqualTo(VideoAnalysisRequestedBy.USER);
        assertThat(opened.getChainId()).isNotEqualTo("chain-a");
        assertThat(opened.getStatus()).isEqualTo(VideoAnalysisStatus.PENDING);
        assertThat(response.status()).isEqualTo(VideoAnalysisResponse.Status.ANALYZING);
    }

    @Test
    void 남의_면접은_보거나_재분석할_수_없다() {
        assertThatThrownBy(() -> service.get(USER_ID + 1, INTERVIEW_ID))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.retry(USER_ID + 1, INTERVIEW_ID))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    /** 켰는데 인증값이 없으면 콜백이나 요청이 모두 튕긴다. 겉으로는 정상처럼 보이므로 배포 시점에 드러낸다. */
    @Test
    void 켰는데_서버_간_인증값이_없으면_뜨지_않는다() {
        ReflectionTestUtils.setField(service, "internalAuthToken", " ");

        assertThatThrownBy(service::requireSettings).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INTERNAL_AUTH_TOKEN");

        ReflectionTestUtils.setField(service, "internalAuthToken", "secret");
        ReflectionTestUtils.setField(service, "videoApiToken", "");
        assertThatThrownBy(service::requireSettings).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VIDEO_ANALYSIS_API_TOKEN");

        ReflectionTestUtils.setField(service, "enabled", false);
        service.requireSettings();

        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "videoApiToken", "video-secret");
        service.requireSettings();
    }

    /** 비어 있을 때 "제한 없음"으로 읽으면 켜는 순간 그동안 쌓인 영상이 모두 나간다. */
    @Test
    void 켰는데_분석_기준_시각이_없거나_틀리면_뜨지_않는다() {
        ReflectionTestUtils.setField(service, "analyzeUploadedAfterSetting", " ");
        assertThatThrownBy(service::requireSettings).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VIDEO_ANALYSIS_UPLOADED_AFTER");

        // 오프셋이 없으면 어느 시간대인지 모른다.
        ReflectionTestUtils.setField(service, "analyzeUploadedAfterSetting", "2026-10-20T09:00:00");
        assertThatThrownBy(service::requireSettings).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VIDEO_ANALYSIS_UPLOADED_AFTER");

        ReflectionTestUtils.setField(service, "analyzeUploadedAfterSetting", "2026-10-20T09:00:00+09:00");
        service.requireSettings();
        assertThat(ReflectionTestUtils.getField(service, "analyzeUploadedAfter")).isEqualTo(
                OffsetDateTime.parse("2026-10-20T09:00:00+09:00").atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime());

        ReflectionTestUtils.setField(service, "enabled", false);
        ReflectionTestUtils.setField(service, "analyzeUploadedAfterSetting", "");
        service.requireSettings();
    }

    @Test
    void 스윕은_분석을_켠_뒤에_올라온_영상만_고른다() {
        LocalDateTime enabledAt = LocalDateTime.now().minusDays(1);
        ReflectionTestUtils.setField(service, "analyzeUploadedAfter", enabledAt);

        service.sweep();

        verify(analysisRepository).findVideosToStart(any(), eq(enabledAt), any());
    }

    @Test
    void 분석을_켠_지_89일이_지나면_보관_기간으로_고른다() {
        ReflectionTestUtils.setField(service, "analyzeUploadedAfter", LocalDateTime.now().minusDays(100));

        service.sweep();

        ArgumentCaptor<LocalDateTime> uploadedAfter = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(analysisRepository).findVideosToStart(any(), uploadedAfter.capture(), any());
        assertThat(uploadedAfter.getValue()).isAfter(LocalDateTime.now().minusDays(90));
    }

    /** 대기로 보이면 웹은 오지 않을 분석을 영영 기다린다. */
    @Test
    void 분석을_켜기_전에_올라온_영상은_NONE이고_재분석을_받지_않는다() {
        // 영상은 하루 전에 올라왔다.
        ReflectionTestUtils.setField(service, "analyzeUploadedAfter", LocalDateTime.now().minusHours(1));

        VideoAnalysisResponse response = service.get(USER_ID, INTERVIEW_ID);

        assertThat(response.status()).isEqualTo(VideoAnalysisResponse.Status.NONE);
        assertThat(response.canRetry()).isFalse();
        assertThatThrownBy(() -> service.retry(USER_ID, INTERVIEW_ID))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        ReflectionTestUtils.setField(service, "analyzeUploadedAfter", LocalDateTime.now().minusDays(2));
        assertThat(service.get(USER_ID, INTERVIEW_ID).status()).isEqualTo(VideoAnalysisResponse.Status.WAITING);
    }

    /** 끄는 것은 새 작업을 막는 것이지 맡긴 작업을 버리는 것이 아니다. 끄는 순간 기다리던 요청이 영영 "분석 중"으로 남지 않게 한다. */
    @Test
    void 꺼져_있으면_새_요청과_자동_재분석은_만들지_않고_맡긴_요청만_끝까지_본다() {
        ReflectionTestUtils.setField(service, "enabled", false);
        VideoAnalysisEntity pending = pending("job-1", 1, LocalDateTime.now().minusMinutes(30));
        when(analysisRepository.findDueChecks(any(), any())).thenReturn(List.of(pending));
        when(aiServerClient.getVideoAnalysisJob("job-1")).thenReturn(new VideoAnalysisJobResponse("job-1", "processing", null, null));

        service.sweep();

        verify(analysisRepository, never()).findVideosToStart(any(), any(), any());
        verify(analysisRepository, never()).findReanalysisCandidates(any(), any(Long.class), any());
        verify(aiServerClient).getVideoAnalysisJob("job-1");
        verify(analysisRepository, never()).save(any());
    }

    @Test
    void 꺼진_채_요청_인증값도_없으면_남은_요청을_실패로_닫지_않고_미룬다() {
        ReflectionTestUtils.setField(service, "enabled", false);
        ReflectionTestUtils.setField(service, "videoApiToken", "");
        when(analysisRepository.findDueChecks(any(), any())).thenReturn(List.of(pending(null, 0, LocalDateTime.now())));

        service.sweep();

        verify(aiServerClient, never()).requestVideoAnalysis(any());
        verify(analysisRepository, never()).fail(any(), any(), any(), any(), any());
    }

    @Test
    void 꺼져_있어도_맡긴_요청은_분석_중이고_저장된_결과는_보이되_재분석은_막힌다() {
        ReflectionTestUtils.setField(service, "enabled", false);
        analyses.add(done(1L, "chain-a", VideoAnalysisRequestedBy.AUTO, VideoAnalysisStatus.PENDING, null, null));
        assertThat(service.get(USER_ID, INTERVIEW_ID).status()).isEqualTo(VideoAnalysisResponse.Status.ANALYZING);

        analyses.clear();
        analyses.add(done(1L, "chain-a", VideoAnalysisRequestedBy.AUTO, VideoAnalysisStatus.PARTIAL, null, Map.of("v", 1)));
        VideoAnalysisResponse partial = service.get(USER_ID, INTERVIEW_ID);
        assertThat(partial.status()).isEqualTo(VideoAnalysisResponse.Status.PARTIAL);
        assertThat(partial.canRetry()).isFalse();
    }

    /** 꺼져 있으면 자동 재분석을 만들지 않는다. "분석 중"으로 보이면 오지 않을 재분석을 기다린다. */
    @Test
    void 꺼져_있으면_재시도할_만한_실패도_자동_재분석_대기가_아니라_실패다() {
        analyses.add(done(1L, "chain-a", VideoAnalysisRequestedBy.AUTO, VideoAnalysisStatus.FAILED, true, null));
        assertThat(service.get(USER_ID, INTERVIEW_ID).status()).isEqualTo(VideoAnalysisResponse.Status.ANALYZING);

        ReflectionTestUtils.setField(service, "enabled", false);
        VideoAnalysisResponse response = service.get(USER_ID, INTERVIEW_ID);

        assertThat(response.status()).isEqualTo(VideoAnalysisResponse.Status.FAILED);
        assertThat(response.canRetry()).isFalse();
    }

    @Test
    void 분석이_꺼져_있고_요청이_없는_영상은_NONE이며_재분석할_수_없다() {
        ReflectionTestUtils.setField(service, "enabled", false);

        VideoAnalysisResponse response = service.get(USER_ID, INTERVIEW_ID);

        assertThat(response.status()).isEqualTo(VideoAnalysisResponse.Status.NONE);
        assertThat(response.reasonCode()).isEqualTo("SERVICE_DISABLED");
        assertThat(response.result()).isNull();
        assertThat(response.canRetry()).isFalse();
        assertThatThrownBy(() -> service.retry(USER_ID, INTERVIEW_ID))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessage("영상 분석을 아직 사용할 수 없습니다.");
        verify(analysisRepository, never()).save(any());
        verify(aiServerClient, never()).requestVideoAnalysis(any());
    }

    // ---- 면접 자격 ----

    @Test
    void 정상_완료되지_않은_면접은_조회와_재분석이_409다() {
        for (Status status : List.of(Status.ABANDONED, Status.IN_PROGRESS)) {
            when(interviewRepository.findById(INTERVIEW_ID)).thenReturn(Optional.of(interview("b1c2d3", status, null)));

            assertThatThrownBy(() -> service.get(USER_ID, INTERVIEW_ID))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
            assertThatThrownBy(() -> service.retry(USER_ID, INTERVIEW_ID))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        }
        verify(analysisRepository, never()).save(any());
    }

    /** 권한·존재 오류는 정책 409보다 먼저다. 남의 중단 면접에 409를 주면 그 면접의 상태가 드러난다. */
    @Test
    void 남의_중단_면접은_409가_아니라_403이다() {
        when(interviewRepository.findById(INTERVIEW_ID)).thenReturn(Optional.of(interview("b1c2d3", Status.ABANDONED, null)));

        assertThatThrownBy(() -> service.get(USER_ID + 1, INTERVIEW_ID))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void 텍스트_면접은_녹화가_있어도_행동_분석을_보이지도_하지도_않는다() {
        when(interviewRepository.findById(INTERVIEW_ID))
                .thenReturn(Optional.of(interview("b1c2d3", Status.COMPLETED, AnswerMode.TEXT)));
        analyses.add(done(1L, "chain-a", VideoAnalysisRequestedBy.AUTO, VideoAnalysisStatus.PARTIAL, null, Map.of("v", 1)));

        VideoAnalysisResponse response = service.get(USER_ID, INTERVIEW_ID);

        assertThat(response.status()).isEqualTo(VideoAnalysisResponse.Status.NONE);
        assertThat(response.reasonCode()).isEqualTo("TEXT_INTERVIEW");
        assertThat(response.result()).isNull();
        assertThat(response.behavior()).isNull();
        assertThatThrownBy(() -> service.retry(USER_ID, INTERVIEW_ID))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void 중단_면접의_대기_요청은_분석_서버를_부르지_않고_정책_사유로_닫는다() {
        when(interviewRepository.findById(INTERVIEW_ID)).thenReturn(Optional.of(interview("b1c2d3", Status.ABANDONED, null)));

        service.check(pending(null, 0, LocalDateTime.now()));
        service.check(pending("job-1", 1, LocalDateTime.now().minusMinutes(30)));

        verify(aiServerClient, never()).requestVideoAnalysis(any());
        verify(aiServerClient, never()).getVideoAnalysisJob(any());
        verify(analysisRepository, org.mockito.Mockito.times(2))
                .fail(eq(ANALYSIS_ID), eq("INTERVIEW_ABANDONED"), eq(false), anyString(), any());
    }

    /** 진행 중인 면접의 요청은 닫지 않는다. 완료로 확정되면 이어서 확인한다. */
    @Test
    void 진행_중인_면접의_대기_요청은_닫지_않고_미룬다() {
        when(interviewRepository.findById(INTERVIEW_ID)).thenReturn(Optional.of(interview("b1c2d3", Status.IN_PROGRESS, null)));

        service.check(pending("job-1", 1, LocalDateTime.now().minusHours(7)));

        verify(aiServerClient, never()).getVideoAnalysisJob(any());
        verify(analysisRepository, never()).fail(any(), any(), any(), any(), any());
        ArgumentCaptor<LocalDateTime> next = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(analysisRepository).postpone(eq(ANALYSIS_ID), next.capture(), anyString(), any());
        assertThat(next.getValue()).isAfter(LocalDateTime.now().plusMinutes(9));
    }

    @Test
    void 잠근_뒤_면접이_중단으로_확정됐으면_첫_요청을_만들지_않는다() {
        when(interviewRepository.lockById(INTERVIEW_ID)).thenReturn(Optional.of(interview("b1c2d3", Status.ABANDONED, null)));

        assertThat(service.openFirst(video(LocalDateTime.now().minusMinutes(5)))).isNull();
        verify(analysisRepository, never()).save(any());
    }

    @Test
    void 잠근_뒤_이미_요청이_있거나_새_영상이_올라왔으면_첫_요청을_만들지_않는다() {
        analyses.add(done(1L, "chain-a", VideoAnalysisRequestedBy.AUTO, VideoAnalysisStatus.FAILED, null, null));
        assertThat(service.openFirst(video(LocalDateTime.now().minusMinutes(5)))).isNull();

        analyses.clear();
        InterviewRecordingEntity stale = InterviewRecordingEntity.builder()
                .recordingId(RECORDING_ID - 1).interviewId(INTERVIEW_ID).userId(USER_ID).kind(RecordingKind.FULL_INTERVIEW)
                .contentType("video/webm").s3Key("k").fileSize(10L).createdAt(LocalDateTime.now().minusMinutes(5)).build();
        assertThat(service.openFirst(stale)).isNull();

        assertThat(service.openFirst(video(LocalDateTime.now().minusMinutes(5)))).isNotNull();
    }

    @Test
    void 결과_문서_없이_실패만_온_작업의_오류도_공개_오류_계약을_따른다() {
        when(aiServerClient.getVideoAnalysisJob("job-1")).thenReturn(new VideoAnalysisJobResponse("job-1", "failed", null,
                Map.of("code", "BEHAVIOR_NOT_MEASURABLE", "message", "부족", "retryable", "false")));

        service.check(pending("job-1", 1, LocalDateTime.now().minusMinutes(30)));

        verify(analysisRepository).fail(eq(ANALYSIS_ID), eq("INVALID_RESULT"), isNull(), anyString(), any());
    }

    @Test
    void 작업_조회의_failed_결과_문서는_콜백과_같은_검증으로_저장한다() {
        Map<String, Object> result = Map.of("status", "failed");
        when(aiServerClient.getVideoAnalysisJob("job-1")).thenReturn(new VideoAnalysisJobResponse("job-1", "failed", result,
                Map.of("code", "INTERNAL_ERROR", "message", "x", "retryable", false)));
        when(callbackHandler.applyRecovered(ANALYSIS_ID, result)).thenReturn(VideoAnalysisCallbackHandler.Outcome.APPLIED);

        service.check(pending("job-1", 1, LocalDateTime.now().minusMinutes(30)));

        verify(callbackHandler).applyRecovered(ANALYSIS_ID, result);
        verify(analysisRepository, never()).fail(any(), any(), any(), any(), any());
    }

    /** 다시 켜며 기준 시각을 옮겨도 이미 정상 대상으로 분석한 영상의 재분석까지 막지 않는다. */
    @Test
    void 분석_기준_시각은_요청이_한_번도_없는_영상에만_적용한다() {
        InterviewRecordingEntity video = video(LocalDateTime.now().minusDays(1));
        ReflectionTestUtils.setField(service, "analyzeUploadedAfter", LocalDateTime.now().minusHours(1));

        assertThat(service.retryBlockedReason(video, List.of(), LocalDateTime.now())).contains("시작하기 전");
        assertThat(service.retryBlockedReason(video, List.of(
                done(1L, "a", VideoAnalysisRequestedBy.AUTO, VideoAnalysisStatus.PARTIAL, null, Map.of())), LocalDateTime.now()))
                .isNull();
    }

    @Test
    void 이미_마친_분석은_canRetry가_거짓이다() {
        analyses.add(done(1L, "chain-a", VideoAnalysisRequestedBy.AUTO, VideoAnalysisStatus.READY, null, Map.of("v", 1)));

        VideoAnalysisResponse response = service.get(USER_ID, INTERVIEW_ID);

        assertThat(response.status()).isEqualTo(VideoAnalysisResponse.Status.READY);
        assertThat(response.canRetry()).isFalse();
        // 행동 데이터로 읽을 수 없는 옛 결과다. 지어내지 않고 사유를 밝힌다.
        assertThat(response.behavior()).isNull();
        assertThat(response.reasonCode()).isEqualTo("RESULT_SCHEMA_UNSUPPORTED");
    }

    // ---- 준비 ----

    private static InterviewEntity interview(String sessionId) {
        return interview(sessionId, Status.COMPLETED, null);
    }

    private static InterviewEntity interview(String sessionId, Status status, AnswerMode answerMode) {
        return InterviewEntity.builder().interviewId(INTERVIEW_ID).userId(USER_ID).sessionId(sessionId)
                .status(status).answerMode(answerMode).build();
    }

    private static InterviewRecordingEntity video(LocalDateTime uploadedAt) {
        return InterviewRecordingEntity.builder()
                .recordingId(RECORDING_ID).interviewId(INTERVIEW_ID).userId(USER_ID)
                .kind(RecordingKind.FULL_INTERVIEW).contentType("video/webm")
                .s3Key("interview-recordings/42/v.webm").fileSize(500_000_000L).createdAt(uploadedAt)
                .build();
    }

    private static VideoAnalysisEntity pending(String jobId, int sendCount, LocalDateTime createdAt) {
        return VideoAnalysisEntity.builder()
                .analysisId(ANALYSIS_ID).interviewId(INTERVIEW_ID).userId(USER_ID).sessionId("b1c2d3")
                .recordingId(RECORDING_ID).requestId("video-1").jobId(jobId).chainId("chain-a")
                .requestedBy(VideoAnalysisRequestedBy.AUTO).status(VideoAnalysisStatus.PENDING)
                .requestPayload(Map.of("requestId", "video-1"))
                .sendCount(sendCount).createdAt(createdAt)
                .build();
    }

    private static VideoAnalysisEntity done(Long id, String chainId, VideoAnalysisRequestedBy requestedBy,
                                            VideoAnalysisStatus status, Boolean retryable, Map<String, Object> result) {
        return VideoAnalysisEntity.builder()
                .analysisId(id).interviewId(INTERVIEW_ID).userId(USER_ID).sessionId("b1c2d3")
                .recordingId(RECORDING_ID).requestId("video-" + id).chainId(chainId)
                .requestedBy(requestedBy).status(status).errorRetryable(retryable).result(result)
                .createdAt(LocalDateTime.now().minusHours(1))
                .build();
    }
}
