package repit.repit_api_server.domain.userdata.analysis.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import repit.repit_api_server.domain.userdata.analysis.behavior.JsonValues;
import repit.repit_api_server.domain.userdata.analysis.entity.VideoAnalysisEntity;
import repit.repit_api_server.domain.userdata.analysis.entity.enums.VideoAnalysisRequestedBy;
import repit.repit_api_server.domain.userdata.analysis.entity.enums.VideoAnalysisStatus;
import repit.repit_api_server.domain.userdata.analysis.repository.VideoAnalysisRepository;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.recording.entity.InterviewRecordingEntity;
import repit.repit_api_server.domain.userdata.recording.entity.enums.RecordingKind;
import repit.repit_api_server.domain.userdata.recording.repository.InterviewRecordingRepository;
import repit.repit_api_server.global.client.AiServerClient;
import repit.repit_api_server.global.common.ApiResponse;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 웹에 내려주는 GET 응답이 Web PR #189의 API 계약 예제(contracts/api-*.json)와 같은지.
 *
 * <p>각 예제의 서버 상태(요청 행·결과 원문·기능 스위치)를 만들고 실제 조회 응답을 {@code {success, data}}로 감싸 비교한다.
 * 필드 이름·null 여부·이전 결과 표시·공개 오류·canRetry까지 같아야 한다. 만든 응답은
 * {@code build/behavior-contract/}에 남긴다 — 웹의 실제 파서로 다시 읽어 보는 데 쓴다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VideoAnalysisViewContractTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Long USER_ID = 7L;
    private static final Long INTERVIEW_ID = 42L;
    private static final Long RECORDING_ID = 501L;
    private static final Path OUTPUT = Path.of("build", "behavior-contract");

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
    private final List<VideoAnalysisEntity> analyses = new ArrayList<>();
    private InterviewRecordingEntity video;

    @BeforeEach
    void setUp() {
        presigner = S3Presigner.builder()
                .region(Region.AP_NORTHEAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new VideoAnalysisService(analysisRepository, interviewRepository, recordingRepository,
                callbackHandler, aiServerClient, presigner, JSON, new TransactionTemplate(transactionManager));
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "analyzeUploadedAfter", LocalDateTime.now().minusDays(30));

        when(interviewRepository.findById(INTERVIEW_ID)).thenReturn(Optional.of(InterviewEntity.builder()
                .interviewId(INTERVIEW_ID).userId(USER_ID).sessionId("session-42").status(Status.COMPLETED).build()));
        video = InterviewRecordingEntity.builder()
                .recordingId(RECORDING_ID).interviewId(INTERVIEW_ID).userId(USER_ID).kind(RecordingKind.FULL_INTERVIEW)
                .contentType("video/webm").s3Key("interview-recordings/42/v.webm").fileSize(10_000_000L)
                .createdAt(LocalDateTime.now().minusDays(1)).build();
        when(recordingRepository.findFirstByInterviewIdAndKindOrderByRecordingIdDesc(INTERVIEW_ID, RecordingKind.FULL_INTERVIEW))
                .thenAnswer(i -> Optional.ofNullable(video));
        when(analysisRepository.findAllByRecordingIdOrderByAnalysisIdAsc(RECORDING_ID)).thenAnswer(i -> List.copyOf(analyses));
    }

    @AfterEach
    void tearDown() {
        presigner.close();
    }

    @Test
    void 정상_결과() {
        analyses.add(row(101, "chain-a", VideoAnalysisRequestedBy.AUTO, VideoAnalysisStatus.READY, fixture("callback-ready")));
        assertMatches("api-ready");
    }

    @Test
    void 부분_결과() {
        analyses.add(row(101, "chain-a", VideoAnalysisRequestedBy.AUTO, VideoAnalysisStatus.PARTIAL, fixture("callback-partial")));
        assertMatches("api-partial");
    }

    @Test
    void 얼굴_0퍼센트() {
        analyses.add(row(101, "chain-a", VideoAnalysisRequestedBy.AUTO, VideoAnalysisStatus.PARTIAL, fixture("callback-zero-face")));
        assertMatches("api-zero-face");
    }

    @Test
    void 재분석_중에는_이전_부분_결과를_보여_준다() {
        analyses.add(row(101, "chain-a", VideoAnalysisRequestedBy.AUTO, VideoAnalysisStatus.PARTIAL, fixture("callback-partial")));
        analyses.add(row(102, "chain-b", VideoAnalysisRequestedBy.USER, VideoAnalysisStatus.PENDING, null));
        assertMatches("api-analyzing-previous");
    }

    /** 최신 요청 102는 자동 재분석 예산(묶음당 5개)을 다 쓰고 실패했다. 사용자 재분석은 한 번 남았고 101의 결과를 계속 보여 준다. */
    @Test
    void 최신_요청이_실패해도_이전_결과를_보여_준다() {
        for (long id = 97; id <= 100; id++) {
            VideoAnalysisEntity failed = row(id, "chain-b", VideoAnalysisRequestedBy.USER, VideoAnalysisStatus.FAILED, null);
            failed.setErrorCode("PROCESSING_OVERDUE");
            failed.setErrorRetryable(true);
            analyses.add(failed);
        }
        analyses.add(row(101, "chain-a", VideoAnalysisRequestedBy.AUTO, VideoAnalysisStatus.PARTIAL, fixture("callback-partial")));
        Map<String, Object> timeout = obj(fixture("api-failed-previous").get("data")).get("result") instanceof Map<?, ?> m
                ? copy(m) : null;
        VideoAnalysisEntity latest = row(102, "chain-b", VideoAnalysisRequestedBy.USER, VideoAnalysisStatus.UNAVAILABLE, timeout);
        latest.setErrorCode("PROCESSING_TIMEOUT");
        latest.setErrorRetryable(true);
        analyses.add(latest);
        assertMatches("api-failed-previous");
    }

    @Test
    void 측정_불가() {
        VideoAnalysisEntity unavailable = row(101, "chain-a", VideoAnalysisRequestedBy.AUTO, VideoAnalysisStatus.UNAVAILABLE,
                fixture("callback-unavailable"));
        unavailable.setErrorCode("BEHAVIOR_NOT_MEASURABLE");
        unavailable.setErrorRetryable(false);
        analyses.add(unavailable);
        assertMatches("api-unavailable");
    }

    @Test
    void 기능이_꺼져_있고_요청이_없다() {
        ReflectionTestUtils.setField(service, "enabled", false);
        ReflectionTestUtils.setField(service, "analyzeUploadedAfter", null);
        assertMatches("api-disabled");
    }

    @Test
    void 녹화가_없다() {
        video = null;
        assertMatches("api-no-recording");
    }

    @Test
    void 요청_전이다() {
        assertMatches("api-waiting");
    }

    // ---- 도구 ----

    private void assertMatches(String name) {
        Map<String, Object> actual = JSON.convertValue(ApiResponse.success(service.get(USER_ID, INTERVIEW_ID)),
                new TypeReference<LinkedHashMap<String, Object>>() {
                });
        Map<String, Object> expected = fixture(name);
        write(name, actual);
        assertThat(JsonValues.same(actual, expected))
                .as("%s%n실제: %s%n예제: %s", name, JSON.writeValueAsString(actual), JSON.writeValueAsString(expected))
                .isTrue();
    }

    private static void write(String name, Map<String, Object> actual) {
        try {
            Files.createDirectories(OUTPUT);
            Files.writeString(OUTPUT.resolve(name + ".json"), JSON.writeValueAsString(actual));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private VideoAnalysisEntity row(long id, String chainId, VideoAnalysisRequestedBy requestedBy, VideoAnalysisStatus status,
                                    Map<String, Object> result) {
        return VideoAnalysisEntity.builder()
                .analysisId(id).interviewId(INTERVIEW_ID).userId(USER_ID).sessionId("session-42")
                .recordingId(RECORDING_ID).requestId("video-request-" + id).chainId(chainId)
                .requestedBy(requestedBy).status(status).result(result)
                .createdAt(LocalDateTime.now().minusHours(1))
                .build();
    }

    private static Map<String, Object> fixture(String name) {
        try (InputStream in = VideoAnalysisViewContractTest.class.getResourceAsStream("/contracts/behavior/" + name + ".json")) {
            return JSON.readValue(in, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> copy(Map<?, ?> value) {
        return new LinkedHashMap<>((Map<String, Object>) value);
    }
}
