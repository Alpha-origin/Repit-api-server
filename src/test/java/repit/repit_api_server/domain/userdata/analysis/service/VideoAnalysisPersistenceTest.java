package repit.repit_api_server.domain.userdata.analysis.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import repit.repit_api_server.domain.userdata.analysis.dto.response.VideoAnalysisAcceptedResponse;
import repit.repit_api_server.domain.userdata.analysis.dto.response.VideoAnalysisJobResponse;
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
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 영상 분석이 실제 스키마 위에서 규칙대로 도는지 본다 — 부분 UNIQUE 인덱스, 조건부 갱신, jsonb, 그리고 스윕이 고르는
 * 쿼리. 이것들은 플러시나 실제 SQL에서야 드러난다.
 *
 * <p>DB 제약 위반을 일으켜야 해서 테스트 트랜잭션으로 감싸지 않는다(위반 뒤 트랜잭션이 깨진다). 만든 행은 끝나고 지운다.
 * 스윕은 한 시간 간격으로 미뤄 테스트 도중 끼어들지 않게 한다.
 */
@SpringBootTest(properties = "app.video-analysis.sweep-interval=1h")
class VideoAnalysisPersistenceTest {

    @Autowired
    private VideoAnalysisService service;
    @Autowired
    private VideoAnalysisCallbackHandler callbackHandler;
    @Autowired
    private VideoAnalysisRepository analysisRepository;
    @Autowired
    private InterviewRepository interviewRepository;
    @Autowired
    private InterviewRecordingRepository recordingRepository;
    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private AiServerClient aiServerClient;

    private final List<Long> interviewIds = new ArrayList<>();
    private final String sessionId = "s-" + UUID.randomUUID();
    private InterviewEntity interview;

    @BeforeEach
    void setUp() {
        interview = interviewRepository.save(InterviewEntity.builder()
                .userId(77L).sessionId(sessionId).status(Status.COMPLETED).build());
        interviewIds.add(interview.getInterviewId());
    }

    @AfterEach
    void tearDown() {
        for (Long interviewId : interviewIds) {
            jdbc.update("delete from unmatched_callback where request_id in "
                    + "(select request_id from video_analysis where interview_id = ?)", interviewId);
            jdbc.update("delete from video_analysis where interview_id = ?", interviewId);
            jdbc.update("delete from interview_recording where interview_id = ?", interviewId);
            jdbc.update("delete from interview where interview_id = ?", interviewId);
        }
        jdbc.update("delete from unmatched_callback where payload like ?", "%" + sessionId + "%");
    }

    @Test
    void 요청_본문은_jsonb로_남고_다시_보낼_때_같은_값으로_나간다() {
        InterviewRecordingEntity video = video(interview, LocalDateTime.now().minusMinutes(5));
        VideoAnalysisEntity opened = service.open(video, "chain-" + UUID.randomUUID(), VideoAnalysisRequestedBy.AUTO);
        when(aiServerClient.requestVideoAnalysis(any())).thenReturn(new VideoAnalysisAcceptedResponse("job-" + sessionId));

        VideoAnalysisEntity stored = analysisRepository.findById(opened.getAnalysisId()).orElseThrow();
        service.check(stored);

        verify(aiServerClient).requestVideoAnalysis(stored.getRequestPayload());
        @SuppressWarnings("unchecked")
        Map<String, Object> sentVideo = (Map<String, Object>) stored.getRequestPayload().get("video");
        assertThat(sentVideo.get("videoId")).isEqualTo(String.valueOf(video.getRecordingId()));
        assertThat((String) sentVideo.get("fileUrl")).contains("X-Amz-Signature=");

        VideoAnalysisEntity acknowledged = analysisRepository.findById(opened.getAnalysisId()).orElseThrow();
        assertThat(acknowledged.getJobId()).isEqualTo("job-" + sessionId);
        assertThat(acknowledged.getSendCount()).isEqualTo(1);
        assertThat(acknowledged.getNextCheckAt()).isAfter(LocalDateTime.now().plusMinutes(9));
    }

    @Test
    void 영상_하나에_살아_있는_요청은_하나뿐이고_새_영상은_막지_않는다() {
        InterviewRecordingEntity first = video(interview, LocalDateTime.now().minusMinutes(5));
        InterviewRecordingEntity second = video(interview, LocalDateTime.now().minusMinutes(4));

        assertThat(service.open(first, "chain-a" + sessionId, VideoAnalysisRequestedBy.AUTO)).isNotNull();
        assertThat(service.open(first, "chain-b" + sessionId, VideoAnalysisRequestedBy.USER)).isNull();
        assertThat(service.open(second, "chain-c" + sessionId, VideoAnalysisRequestedBy.AUTO)).isNotNull();
        assertThatThrownBy(() -> analysisRepository.save(row(first, "chain-d", VideoAnalysisStatus.PENDING, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 콜백_결과는_jsonb로_저장되고_재전송에도_한_행이다() {
        VideoAnalysisEntity pending = analysisRepository.save(
                row(video(interview, LocalDateTime.now()), "chain-a", VideoAnalysisStatus.PENDING, null));
        String body = callback(pending, "job-" + sessionId, "unavailable",
                ",\"error\":{\"code\":\"PROCESSING_TIMEOUT\",\"message\":\"t\",\"retryable\":true},\"metrics\":{\"gaze\":0.8}");

        callbackHandler.handle(body);
        callbackHandler.handle(body);

        VideoAnalysisEntity stored = analysisRepository.findById(pending.getAnalysisId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(VideoAnalysisStatus.UNAVAILABLE);
        assertThat(stored.getJobId()).isEqualTo("job-" + sessionId);
        assertThat(stored.getErrorCode()).isEqualTo("PROCESSING_TIMEOUT");
        assertThat(stored.getErrorRetryable()).isTrue();
        assertThat(stored.getResult()).containsKey("metrics");
        assertThat(analysisRepository.findAllByRecordingIdOrderByAnalysisIdAsc(pending.getRecordingId())).hasSize(1);
        assertThat(unmatchedCount()).isZero();
    }

    @Test
    void 모르는_요청이나_식별자가_다른_결과는_원문만_따로_남기고_반영하지_않는다() {
        VideoAnalysisEntity pending = analysisRepository.save(
                row(video(interview, LocalDateTime.now()), "chain-a", VideoAnalysisStatus.PENDING, null));

        callbackHandler.handle(callback(pending, "job-" + sessionId, "ready", "")
                .replace(pending.getRequestId(), "video-unknown-" + sessionId));
        callbackHandler.handle(callback(pending, "job-" + sessionId, "ready", "")
                .replace("\"interviewId\":\"" + interview.getInterviewId() + "\"", "\"interviewId\":\"1\""));
        callbackHandler.handle(callback(pending, "job-" + sessionId, "done", ""));
        callbackHandler.handle(callback(pending, "job-" + sessionId, "ready", "").replace("\"schemaVersion\":\"1\"", "\"schemaVersion\":\"2\""));
        callbackHandler.handle("not json " + sessionId);

        assertThat(jdbc.queryForList("select reason from unmatched_callback where payload like ? order by callback_id",
                String.class, "%" + sessionId + "%"))
                .containsExactly("UNKNOWN_REQUEST", "ID_MISMATCH", "INVALID_BODY", "INVALID_BODY", "INVALID_BODY");
        VideoAnalysisEntity untouched = analysisRepository.findById(pending.getAnalysisId()).orElseThrow();
        assertThat(untouched.getStatus()).isEqualTo(VideoAnalysisStatus.PENDING);
        assertThat(untouched.getResult()).isNull();
    }

    @Test
    void 결과가_먼저_들어오면_실패로_닫는_갱신이_덮지_않는다() {
        VideoAnalysisEntity pending = analysisRepository.save(
                row(video(interview, LocalDateTime.now()), "chain-a", VideoAnalysisStatus.PENDING, null));
        callbackHandler.handle(callback(pending, "job-" + sessionId, "ready", ""));

        int updated = analysisRepository.fail(pending.getAnalysisId(), "PROCESSING_OVERDUE", null, "늦음", LocalDateTime.now());

        assertThat(updated).isZero();
        assertThat(analysisRepository.findById(pending.getAnalysisId()).orElseThrow().getStatus())
                .isEqualTo(VideoAnalysisStatus.READY);
    }

    @Test
    void 처음_요청할_영상은_조용해진_최종_영상이고_요청_기록이_없는_것뿐이다() {
        InterviewRecordingEntity older = video(interview, LocalDateTime.now().minusMinutes(10));
        InterviewRecordingEntity latest = video(interview, LocalDateTime.now().minusMinutes(3));

        InterviewEntity busy = otherInterview();
        InterviewRecordingEntity justUploaded = video(busy, LocalDateTime.now().minusSeconds(30));

        InterviewEntity done = otherInterview();
        InterviewRecordingEntity alreadyRequested = video(done, LocalDateTime.now().minusMinutes(10));
        analysisRepository.save(row(alreadyRequested, "chain-x", VideoAnalysisStatus.READY, null));

        List<Long> picked = toStart();

        assertThat(picked).contains(latest.getRecordingId())
                .doesNotContain(older.getRecordingId(), justUploaded.getRecordingId(), alreadyRequested.getRecordingId());
    }

    @Test
    void 자동_재분석은_최종_영상의_최신_요청을_묶음당_작업_5개까지만_잇는다() {
        InterviewRecordingEntity video = video(interview, LocalDateTime.now().minusHours(1));
        String chain = "chain-" + UUID.randomUUID();
        for (int i = 0; i < 4; i++) {
            analysisRepository.save(row(video, chain, VideoAnalysisStatus.FAILED, true));
        }
        backdateUpdates();
        assertThat(candidates()).hasSize(1);

        // 다섯 번째 작업까지 실패하면 더 잇지 않는다.
        analysisRepository.save(row(video, chain, VideoAnalysisStatus.FAILED, true));
        backdateUpdates();
        assertThat(candidates()).isEmpty();

        // 새 영상이 올라오면 옛 영상의 실패는 잇지 않는다(새 영상이 따로 요청된다).
        InterviewEntity other = otherInterview();
        InterviewRecordingEntity replaced = video(other, LocalDateTime.now().minusHours(1));
        analysisRepository.save(row(replaced, "chain-" + UUID.randomUUID(), VideoAnalysisStatus.FAILED, true));
        video(other, LocalDateTime.now().minusMinutes(30));
        backdateUpdates();
        assertThat(candidates()).isEmpty();
    }

    @Test
    void 재시도_불가이거나_부분_결과인_요청은_자동으로_잇지_않는다() {
        InterviewRecordingEntity video = video(interview, LocalDateTime.now().minusHours(1));
        analysisRepository.save(row(video, "chain-" + UUID.randomUUID(), VideoAnalysisStatus.FAILED, false));
        backdateUpdates();
        assertThat(candidates()).isEmpty();

        analysisRepository.save(row(video, "chain-" + UUID.randomUUID(), VideoAnalysisStatus.PARTIAL, true));
        backdateUpdates();
        assertThat(candidates()).isEmpty();
    }

    // ---- 분석 서버 계약 예시(repit-analysis-server-v2 docs/video-api.md)를 그대로 넣어 본다 ----

    @Test
    void 분석기_미연결_결과는_분석_오류를_남긴_UNAVAILABLE이다() {
        VideoAnalysisEntity pending = pendingRow();

        callbackHandler.handle(example(CALLBACK_UNCONFIGURED, pending, "job-" + UUID.randomUUID()));

        VideoAnalysisEntity stored = reload(pending);
        assertThat(stored.getStatus()).isEqualTo(VideoAnalysisStatus.UNAVAILABLE);
        assertThat(stored.getErrorCode()).isEqualTo("ANALYZER_NOT_CONFIGURED");
        assertThat(stored.getErrorRetryable()).isFalse();
        // 웹은 result.result.analysis를 읽는다. 본문 전체를 그대로 둔다.
        assertThat(stored.getResult()).containsKey("result").containsEntry("status", "unavailable");
        assertThat(unmatchedCount()).isZero();
    }

    @Test
    void 파일_문제_결과는_파일_오류를_남긴_UNAVAILABLE이다() {
        VideoAnalysisEntity pending = pendingRow();

        callbackHandler.handle(example(CALLBACK_FILE_ERROR, pending, "job-" + UUID.randomUUID()));

        VideoAnalysisEntity stored = reload(pending);
        assertThat(stored.getStatus()).isEqualTo(VideoAnalysisStatus.UNAVAILABLE);
        assertThat(stored.getErrorCode()).isEqualTo("VIDEO_FORMAT_UNSUPPORTED");
        assertThat(stored.getErrorRetryable()).isFalse();
        assertThat(unmatchedCount()).isZero();
    }

    @Test
    void 문서_생성_실패는_결과_없이_FAILED로_닫는다() {
        VideoAnalysisEntity pending = pendingRow();

        callbackHandler.handle(example(CALLBACK_FAILED, pending, "job-" + UUID.randomUUID()));

        VideoAnalysisEntity stored = reload(pending);
        assertThat(stored.getStatus()).isEqualTo(VideoAnalysisStatus.FAILED);
        assertThat(stored.getErrorCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(stored.getErrorRetryable()).isFalse();
        assertThat(stored.getResult()).isNull();
        assertThat(unmatchedCount()).isZero();
    }

    @Test
    void 분석_완료_결과는_READY로_남는다() {
        VideoAnalysisEntity pending = pendingRow();

        callbackHandler.handle(example(CALLBACK_READY, pending, "job-" + UUID.randomUUID()));

        VideoAnalysisEntity stored = reload(pending);
        assertThat(stored.getStatus()).isEqualTo(VideoAnalysisStatus.READY);
        assertThat(stored.getErrorCode()).isNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> analysis = (Map<String, Object>) ((Map<String, Object>) stored.getResult().get("result")).get("analysis");
        assertThat(analysis.get("data")).isEqualTo(Map.of("gaze", 0.8));
    }

    @Test
    void 다른_녹화의_결과는_ID_MISMATCH로_따로_남기고_행은_그대로다() {
        VideoAnalysisEntity pending = pendingRow();

        callbackHandler.handle(example(CALLBACK_UNCONFIGURED, pending, "job-" + UUID.randomUUID())
                .replace("\"videoId\": \"" + pending.getRecordingId() + "\"", "\"videoId\": \"" + (pending.getRecordingId() + 1) + "\""));

        assertThat(jdbc.queryForList("select reason from unmatched_callback where payload like ?",
                String.class, "%" + sessionId + "%")).containsExactly("ID_MISMATCH");
        VideoAnalysisEntity untouched = reload(pending);
        assertThat(untouched.getStatus()).isEqualTo(VideoAnalysisStatus.PENDING);
        assertThat(untouched.getResult()).isNull();
    }

    @Test
    void 재시도할_만한_파일_오류는_자동_재분석으로_잇는다() {
        VideoAnalysisEntity pending = pendingRow();

        callbackHandler.handle(example(CALLBACK_FILE_ERROR, pending, "job-" + UUID.randomUUID())
                .replace("VIDEO_FORMAT_UNSUPPORTED", "SOURCE_DOWNLOAD_FAILED")
                .replace("\"retryable\": false", "\"retryable\": true"));
        backdateUpdates();

        VideoAnalysisEntity stored = reload(pending);
        assertThat(stored.getErrorCode()).isEqualTo("SOURCE_DOWNLOAD_FAILED");
        assertThat(stored.getErrorRetryable()).isTrue();
        assertThat(candidates()).extracting(VideoAnalysisEntity::getAnalysisId).containsExactly(pending.getAnalysisId());
    }

    @Test
    void 작업_조회로_받은_결과는_콜백과_같게_저장된다() {
        VideoAnalysisEntity unavailableByCallback = pendingRow();
        callbackHandler.handle(example(CALLBACK_UNCONFIGURED, unavailableByCallback, "job-" + UUID.randomUUID()));
        VideoAnalysisEntity unavailableByJob = pendingRow("job-" + UUID.randomUUID());
        when(aiServerClient.getVideoAnalysisJob(unavailableByJob.getJobId())).thenReturn(new VideoAnalysisJobResponse(
                unavailableByJob.getJobId(), "completed",
                json(example(CALLBACK_UNCONFIGURED, unavailableByJob, unavailableByJob.getJobId())), null));
        service.check(unavailableByJob);

        VideoAnalysisEntity failedByCallback = pendingRow();
        callbackHandler.handle(example(CALLBACK_FAILED, failedByCallback, "job-" + UUID.randomUUID()));
        // snapshot-failed: result는 실패 콜백 본문 전체, error는 그 error와 같다.
        VideoAnalysisEntity failedByJob = pendingRow("job-" + UUID.randomUUID());
        Map<String, Object> failedBody = json(example(CALLBACK_FAILED, failedByJob, failedByJob.getJobId()));
        @SuppressWarnings("unchecked")
        Map<String, Object> failedError = (Map<String, Object>) failedBody.get("error");
        when(aiServerClient.getVideoAnalysisJob(failedByJob.getJobId())).thenReturn(new VideoAnalysisJobResponse(
                failedByJob.getJobId(), "failed", failedBody, failedError));
        service.check(failedByJob);

        assertSameOutcome(reload(unavailableByJob), reload(unavailableByCallback));
        assertSameOutcome(reload(failedByJob), reload(failedByCallback));
        assertThat(reload(failedByJob).getStatus()).isEqualTo(VideoAnalysisStatus.FAILED);
        assertThat(unmatchedCount()).isZero();
    }

    private static void assertSameOutcome(VideoAnalysisEntity actual, VideoAnalysisEntity expected) {
        assertThat(actual.getStatus()).isEqualTo(expected.getStatus());
        assertThat(actual.getErrorCode()).isEqualTo(expected.getErrorCode());
        assertThat(actual.getErrorRetryable()).isEqualTo(expected.getErrorRetryable());
        assertThat(actual.getErrorMessage()).isEqualTo(expected.getErrorMessage());
        assertThat(actual.getResult() == null).isEqualTo(expected.getResult() == null);
    }

    // ---- 준비 ----

    /** 그 면접의 새 영상에 결과를 기다리는 요청 하나. */
    private VideoAnalysisEntity pendingRow() {
        return pendingRow(null);
    }

    private VideoAnalysisEntity pendingRow(String jobId) {
        VideoAnalysisEntity row = row(video(interview, LocalDateTime.now()), "chain-" + UUID.randomUUID(),
                VideoAnalysisStatus.PENDING, null);
        row.setJobId(jobId);
        return analysisRepository.save(row);
    }

    private VideoAnalysisEntity reload(VideoAnalysisEntity analysis) {
        return analysisRepository.findById(analysis.getAnalysisId()).orElseThrow();
    }

    /** 계약 예시의 식별자를 이 요청의 값으로 바꾼다. 나머지는 그대로다. */
    private static String example(String body, VideoAnalysisEntity analysis, String jobId) {
        return body.replace("\"9f0c1e\"", "\"" + jobId + "\"")
                .replace("\"video-request-001\"", "\"" + analysis.getRequestId() + "\"")
                .replace("\"b1c2d3\"", "\"" + analysis.getSessionId() + "\"")
                .replace("\"interviewId\": \"42\"", "\"interviewId\": \"" + analysis.getInterviewId() + "\"")
                .replace("\"userId\": \"7\"", "\"userId\": \"" + analysis.getUserId() + "\"")
                .replace("\"videoId\": \"501\"", "\"videoId\": \"" + analysis.getRecordingId() + "\"");
    }

    private static Map<String, Object> json(String body) {
        return JsonMapper.builder().build().readValue(body, new TypeReference<>() {
        });
    }

    private static final String CALLBACK_UNCONFIGURED = """
            {
              "schemaVersion": "1",
              "jobId": "9f0c1e",
              "requestId": "video-request-001",
              "sessionId": "b1c2d3",
              "interviewId": "42",
              "userId": "7",
              "status": "unavailable",
              "result": {
                "videoId": "501",
                "durationMs": 734120,
                "status": "unavailable",
                "analysis": {
                  "status": "unavailable",
                  "data": null,
                  "error": {"code": "ANALYZER_NOT_CONFIGURED", "message": "영상 행동 분석기가 아직 연결되지 않았습니다.", "retryable": false}
                },
                "error": null
              },
              "error": null
            }
            """;

    private static final String CALLBACK_FILE_ERROR = """
            {
              "schemaVersion": "1",
              "jobId": "9f0c1e",
              "requestId": "video-request-001",
              "sessionId": "b1c2d3",
              "interviewId": "42",
              "userId": "7",
              "status": "unavailable",
              "result": {
                "videoId": "501",
                "durationMs": null,
                "status": "unavailable",
                "analysis": {
                  "status": "unavailable",
                  "data": null,
                  "error": {"code": "VIDEO_FORMAT_UNSUPPORTED", "message": "지원하지 않는 영상 형식입니다. WebM(VP8/VP9) 또는 MP4(H.264)만 지원합니다.", "retryable": false}
                },
                "error": {"code": "VIDEO_FORMAT_UNSUPPORTED", "message": "지원하지 않는 영상 형식입니다. WebM(VP8/VP9) 또는 MP4(H.264)만 지원합니다.", "retryable": false}
              },
              "error": null
            }
            """;

    private static final String CALLBACK_FAILED = """
            {
              "schemaVersion": "1",
              "jobId": "9f0c1e",
              "requestId": "video-request-001",
              "sessionId": "b1c2d3",
              "interviewId": "42",
              "userId": "7",
              "status": "failed",
              "result": null,
              "error": {"code": "INTERNAL_ERROR", "message": "영상 분석 서버 내부 오류가 발생했습니다.", "retryable": false}
            }
            """;

    // 계약에 ready 예시는 없다(분석기 미연결). 미연결 예시에서 상태와 analysis만 바꿨다. data 모양은 아직 정해지지 않았다.
    private static final String CALLBACK_READY = """
            {
              "schemaVersion": "1",
              "jobId": "9f0c1e",
              "requestId": "video-request-001",
              "sessionId": "b1c2d3",
              "interviewId": "42",
              "userId": "7",
              "status": "ready",
              "result": {
                "videoId": "501",
                "durationMs": 734120,
                "status": "ready",
                "analysis": {"status": "ready", "data": {"gaze": 0.8}, "error": null},
                "error": null
              },
              "error": null
            }
            """;

    private List<Long> toStart() {
        LocalDateTime now = LocalDateTime.now();
        return analysisRepository.findVideosToStart(now.minusMinutes(2), now.minusDays(89), Limit.of(10_000)).stream()
                .map(InterviewRecordingEntity::getRecordingId)
                .toList();
    }

    private List<VideoAnalysisEntity> candidates() {
        return analysisRepository.findReanalysisCandidates(LocalDateTime.now().minusMinutes(1),
                        VideoAnalysisService.MAX_JOBS_PER_CHAIN, Limit.of(10_000)).stream()
                .filter(candidate -> interviewIds.contains(candidate.getInterviewId()))
                .toList();
    }

    /** 자동 재분석은 실패 뒤 조금 기다렸다 잇는다. 기다린 것으로 만든다. */
    private void backdateUpdates() {
        for (Long interviewId : interviewIds) {
            jdbc.update("update video_analysis set updated_at = ? where interview_id = ?",
                    LocalDateTime.now().minusMinutes(5), interviewId);
        }
    }

    private InterviewEntity otherInterview() {
        InterviewEntity other = interviewRepository.save(InterviewEntity.builder()
                .userId(77L).sessionId("s-" + UUID.randomUUID()).status(Status.COMPLETED).build());
        interviewIds.add(other.getInterviewId());
        return other;
    }

    private InterviewRecordingEntity video(InterviewEntity owner, LocalDateTime uploadedAt) {
        InterviewRecordingEntity saved = recordingRepository.save(InterviewRecordingEntity.builder()
                .interviewId(owner.getInterviewId()).userId(owner.getUserId()).kind(RecordingKind.FULL_INTERVIEW)
                .contentType("video/mp4").s3Key("interview-recordings/" + owner.getInterviewId() + "/v.mp4")
                .fileSize(1024L).build());
        jdbc.update("update interview_recording set created_at = ? where recording_id = ?", uploadedAt, saved.getRecordingId());
        return recordingRepository.findById(saved.getRecordingId()).orElseThrow();
    }

    private VideoAnalysisEntity row(InterviewRecordingEntity video, String chainId, VideoAnalysisStatus status,
                                    Boolean retryable) {
        return VideoAnalysisEntity.builder()
                .interviewId(video.getInterviewId()).userId(video.getUserId()).sessionId(sessionId)
                .recordingId(video.getRecordingId()).requestId("video-" + UUID.randomUUID()).chainId(chainId)
                .requestedBy(VideoAnalysisRequestedBy.AUTO).status(status).errorRetryable(retryable)
                .requestPayload(Map.of("requestId", "x")).nextCheckAt(LocalDateTime.now())
                .build();
    }

    private String callback(VideoAnalysisEntity analysis, String jobId, String status, String extra) {
        return "{\"jobId\":\"" + jobId + "\",\"requestId\":\"" + analysis.getRequestId() + "\""
                + ",\"sessionId\":\"" + sessionId + "\",\"interviewId\":\"" + analysis.getInterviewId() + "\""
                + ",\"userId\":\"" + analysis.getUserId() + "\",\"result\":{\"videoId\":\"" + analysis.getRecordingId() + "\"}"
                + ",\"schemaVersion\":\"1\",\"status\":\"" + status + "\"" + extra + "}";
    }

    private int unmatchedCount() {
        return jdbc.queryForObject("select count(*) from unmatched_callback where payload like ?",
                Integer.class, "%" + sessionId + "%");
    }
}
