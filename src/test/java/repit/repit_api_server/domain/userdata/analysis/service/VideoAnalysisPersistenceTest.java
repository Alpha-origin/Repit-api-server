package repit.repit_api_server.domain.userdata.analysis.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
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
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 영상 분석이 실제 PostgreSQL 위에서 규칙대로 도는지 본다 — 부분 UNIQUE 인덱스, 조건부 갱신, 행 잠금, jsonb, 그리고 스윕이
 * 고르는 쿼리. 이것들은 플러시나 실제 SQL, 두 연결이 겹칠 때에야 드러난다.
 *
 * <p>결과 본문은 Web PR #189의 계약 예제(contracts/callback-*.json)에서 식별자만 이 요청의 값으로 바꿔 쓴다.
 *
 * <p>DB 제약 위반과 두 연결의 경쟁을 일으켜야 해서 테스트 트랜잭션으로 감싸지 않는다. 만든 행은 끝나고 지운다.
 * 스윕은 한 시간 간격으로 미뤄 테스트 도중 끼어들지 않게 한다.
 */
@SpringBootTest(properties = "app.video-analysis.sweep-interval=1h")
class VideoAnalysisPersistenceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

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
    private TransactionTemplate transactionTemplate;
    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private AiServerClient aiServerClient;

    private final List<Long> interviewIds = new ArrayList<>();
    private final String sessionId = "s-" + UUID.randomUUID();
    private InterviewEntity interview;
    private Object enabledSetting;
    private Object uploadedAfterSetting;
    private Object videoApiTokenSetting;

    @BeforeEach
    void setUp() {
        enabledSetting = ReflectionTestUtils.getField(service, "enabled");
        uploadedAfterSetting = ReflectionTestUtils.getField(service, "analyzeUploadedAfter");
        videoApiTokenSetting = ReflectionTestUtils.getField(service, "videoApiToken");
        interview = interviewRepository.save(InterviewEntity.builder()
                .userId(77L).sessionId(sessionId).status(Status.COMPLETED).build());
        interviewIds.add(interview.getInterviewId());
    }

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(service, "enabled", enabledSetting);
        ReflectionTestUtils.setField(service, "analyzeUploadedAfter", uploadedAfterSetting);
        ReflectionTestUtils.setField(service, "videoApiToken", videoApiTokenSetting);
        for (Long interviewId : interviewIds) {
            jdbc.update("delete from unmatched_callback where request_id in "
                    + "(select request_id from video_analysis where interview_id = ?)", interviewId);
            jdbc.update("delete from video_analysis where interview_id = ?", interviewId);
            jdbc.update("delete from interview_recording where interview_id = ?", interviewId);
            jdbc.update("delete from interview where interview_id = ?", interviewId);
        }
        jdbc.update("delete from unmatched_callback where payload like ? or detail like ?",
                "%" + sessionId + "%", "%" + sessionId + "%");
    }

    // ---- 요청 자리 ----

    @Test
    void 요청_본문은_jsonb로_남고_다시_보낼_때_같은_값으로_나간다() {
        InterviewRecordingEntity video = video(interview, LocalDateTime.now().minusMinutes(5));
        VideoAnalysisEntity opened = service.openFirst(video);
        when(aiServerClient.requestVideoAnalysis(any())).thenReturn(new VideoAnalysisAcceptedResponse("job-" + sessionId));

        VideoAnalysisEntity stored = reload(opened);
        service.check(stored);

        verify(aiServerClient).requestVideoAnalysis(stored.getRequestPayload());
        @SuppressWarnings("unchecked")
        Map<String, Object> sentVideo = (Map<String, Object>) stored.getRequestPayload().get("video");
        assertThat(sentVideo.get("videoId")).isEqualTo(String.valueOf(video.getRecordingId()));
        assertThat((String) sentVideo.get("fileUrl")).contains("X-Amz-Signature=");

        VideoAnalysisEntity acknowledged = reload(opened);
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

    // ---- 스윕이 고르는 쿼리 ----

    @Test
    void 처음_요청할_영상은_조용해진_최종_영상이고_요청_기록이_없는_것뿐이다() {
        InterviewRecordingEntity older = video(interview, LocalDateTime.now().minusMinutes(10));
        InterviewRecordingEntity latest = video(interview, LocalDateTime.now().minusMinutes(3));

        InterviewEntity busy = otherInterview(Status.COMPLETED, null);
        InterviewRecordingEntity justUploaded = video(busy, LocalDateTime.now().minusSeconds(30));

        InterviewEntity done = otherInterview(Status.COMPLETED, null);
        InterviewRecordingEntity alreadyRequested = video(done, LocalDateTime.now().minusMinutes(10));
        analysisRepository.save(row(alreadyRequested, "chain-x", VideoAnalysisStatus.READY, null));

        assertThat(toStart()).contains(latest.getRecordingId())
                .doesNotContain(older.getRecordingId(), justUploaded.getRecordingId(), alreadyRequested.getRecordingId());
    }

    /** 영상은 면접 상태와 상관없이 받아 두지만, 분석은 정상 완료된 음성 면접만 한다. */
    @Test
    void 중단_진행_중_텍스트_면접의_영상은_처음_요청할_영상으로_고르지_않는다() {
        InterviewRecordingEntity completed = video(interview, LocalDateTime.now().minusMinutes(10));
        InterviewRecordingEntity abandoned = video(otherInterview(Status.ABANDONED, null), LocalDateTime.now().minusMinutes(10));
        InterviewEntity running = otherInterview(Status.IN_PROGRESS, AnswerMode.VOICE);
        InterviewRecordingEntity inProgress = video(running, LocalDateTime.now().minusMinutes(10));
        InterviewRecordingEntity text = video(otherInterview(Status.COMPLETED, AnswerMode.TEXT), LocalDateTime.now().minusMinutes(10));

        assertThat(toStart()).contains(completed.getRecordingId())
                .doesNotContain(abandoned.getRecordingId(), inProgress.getRecordingId(), text.getRecordingId());

        // 진행 중에 먼저 올라온 영상은 버리지 않는다. 완료로 확정되면 고른다.
        jdbc.update("update interview set status = 'COMPLETED' where interview_id = ?", running.getInterviewId());
        assertThat(toStart()).contains(inProgress.getRecordingId());
    }

    /** 스윕은 분석을 켠 시각을 uploadedAfter로 넘긴다. 그 시각까지 올라온 영상은 고르지 않는다. */
    @Test
    void 분석을_켜기_전에_올라온_영상은_처음_요청할_영상으로_고르지_않는다() {
        LocalDateTime enabledAt = LocalDateTime.now().minusHours(1);
        InterviewRecordingEntity before = video(interview, enabledAt.minusMinutes(1));

        InterviewEntity later = otherInterview(Status.COMPLETED, null);
        InterviewRecordingEntity after = video(later, enabledAt.plusMinutes(1));

        LocalDateTime now = LocalDateTime.now();
        List<Long> picked = analysisRepository.findVideosToStart(now.minusMinutes(2), enabledAt, Limit.of(10_000)).stream()
                .map(InterviewRecordingEntity::getRecordingId)
                .toList();

        assertThat(picked).contains(after.getRecordingId()).doesNotContain(before.getRecordingId());
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
        InterviewEntity other = otherInterview(Status.COMPLETED, null);
        InterviewRecordingEntity replaced = video(other, LocalDateTime.now().minusHours(1));
        analysisRepository.save(row(replaced, "chain-" + UUID.randomUUID(), VideoAnalysisStatus.FAILED, true));
        video(other, LocalDateTime.now().minusMinutes(30));
        backdateUpdates();
        assertThat(candidates()).isEmpty();
    }

    @Test
    void 재시도_불가이거나_부분_결과이거나_중단된_면접의_요청은_자동으로_잇지_않는다() {
        InterviewRecordingEntity video = video(interview, LocalDateTime.now().minusHours(1));
        analysisRepository.save(row(video, "chain-" + UUID.randomUUID(), VideoAnalysisStatus.FAILED, false));
        backdateUpdates();
        assertThat(candidates()).isEmpty();

        analysisRepository.save(row(video, "chain-" + UUID.randomUUID(), VideoAnalysisStatus.PARTIAL, true));
        backdateUpdates();
        assertThat(candidates()).isEmpty();

        InterviewRecordingEntity abandoned = video(otherInterview(Status.ABANDONED, null), LocalDateTime.now().minusHours(1));
        analysisRepository.save(row(abandoned, "chain-" + UUID.randomUUID(), VideoAnalysisStatus.FAILED, true));
        backdateUpdates();
        assertThat(candidates()).isEmpty();
    }

    // ---- 결과 저장(계약 예제를 그대로 넣는다) ----

    @Test
    void 분석_완료_결과는_jsonb로_READY로_남고_같은_문서의_재도착은_한_행이다() {
        VideoAnalysisEntity pending = pendingRow();
        String body = callback("callback-ready", pending, "job-" + UUID.randomUUID());

        assertThat(callbackHandler.handle(body)).isEqualTo(VideoAnalysisCallbackHandler.Outcome.APPLIED);
        assertThat(callbackHandler.handle(body)).isEqualTo(VideoAnalysisCallbackHandler.Outcome.DUPLICATE);

        VideoAnalysisEntity stored = reload(pending);
        assertThat(stored.getStatus()).isEqualTo(VideoAnalysisStatus.READY);
        assertThat(stored.getErrorCode()).isNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> analysis = (Map<String, Object>) ((Map<String, Object>) stored.getResult().get("result")).get("analysis");
        assertThat(analysis.get("data")).isInstanceOf(Map.class);
        assertThat(analysisRepository.findAllByRecordingIdOrderByAnalysisIdAsc(pending.getRecordingId())).hasSize(1);
        assertThat(unmatchedCount()).isZero();
    }

    @Test
    void 분석기_미연결_결과는_분석_오류를_남긴_UNAVAILABLE이다() {
        VideoAnalysisEntity pending = pendingRow();

        callbackHandler.handle(callback("callback-unconfigured", pending, "job-" + UUID.randomUUID()));

        VideoAnalysisEntity stored = reload(pending);
        assertThat(stored.getStatus()).isEqualTo(VideoAnalysisStatus.UNAVAILABLE);
        assertThat(stored.getErrorCode()).isEqualTo("ANALYZER_NOT_CONFIGURED");
        assertThat(stored.getErrorRetryable()).isFalse();
        assertThat(stored.getResult()).containsKey("result").containsEntry("status", "unavailable");
        assertThat(unmatchedCount()).isZero();
    }

    @Test
    void 측정_불가는_재시도하지_않는_UNAVAILABLE이다() {
        VideoAnalysisEntity pending = pendingRow();

        callbackHandler.handle(callback("callback-unavailable", pending, "job-" + UUID.randomUUID()));
        backdateUpdates();

        VideoAnalysisEntity stored = reload(pending);
        assertThat(stored.getErrorCode()).isEqualTo("BEHAVIOR_NOT_MEASURABLE");
        assertThat(stored.getErrorRetryable()).isFalse();
        assertThat(candidates()).isEmpty();
    }

    @Test
    void 재시도할_만한_파일_오류는_자동_재분석으로_잇는다() {
        VideoAnalysisEntity pending = pendingRow();
        Map<String, Object> body = fixture("callback-unconfigured");
        Map<String, Object> error = Map.of("code", "SOURCE_DOWNLOAD_FAILED", "message", "영상 파일을 내려받지 못했습니다.",
                "retryable", true);
        result(body).put("durationMs", null);
        result(body).put("error", error);
        analysisOf(body).put("error", error);

        callbackHandler.handle(callback(body, pending, "job-" + UUID.randomUUID()));
        backdateUpdates();

        VideoAnalysisEntity stored = reload(pending);
        assertThat(stored.getErrorCode()).isEqualTo("SOURCE_DOWNLOAD_FAILED");
        assertThat(stored.getErrorRetryable()).isTrue();
        assertThat(candidates()).extracting(VideoAnalysisEntity::getAnalysisId).containsExactly(pending.getAnalysisId());
    }

    /** failed 문서도 원문을 남긴다. 이 서버가 결과 없이 닫은 행과 가르는 단서다. 화면의 호환용 result에는 쓰지 않는다. */
    @Test
    void 문서_생성_실패는_FAILED로_닫고_원문을_남긴다() {
        VideoAnalysisEntity pending = pendingRow();

        callbackHandler.handle(callback("callback-failed", pending, "job-" + UUID.randomUUID()));

        VideoAnalysisEntity stored = reload(pending);
        assertThat(stored.getStatus()).isEqualTo(VideoAnalysisStatus.FAILED);
        assertThat(stored.getErrorCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(stored.getErrorRetryable()).isFalse();
        assertThat(stored.getResult()).containsEntry("status", "failed");
        assertThat(unmatchedCount()).isZero();
    }

    @Test
    void 모르는_요청이나_식별자가_다르거나_계약에_맞지_않는_결과는_원문만_따로_남긴다() {
        VideoAnalysisEntity pending = pendingRow();
        String jobId = "job-" + UUID.randomUUID();

        Map<String, Object> unknown = parse(callback("callback-ready", pending, jobId));
        unknown.put("requestId", "video-unknown-" + sessionId);
        callbackHandler.handle(JSON.writeValueAsString(unknown));
        Map<String, Object> otherVideo = parse(callback("callback-unconfigured", pending, jobId));
        result(otherVideo).put("videoId", String.valueOf(pending.getRecordingId() + 1));
        callbackHandler.handle(JSON.writeValueAsString(otherVideo));
        callbackHandler.handle(callback("callback-ready", pending, jobId).replace("\"ready\"", "\"done\""));
        callbackHandler.handle(callback("callback-ready", pending, jobId).replace("\"schemaVersion\":\"1\"", "\"schemaVersion\":\"2\""));
        // 계약 위반: ready인데 행동 데이터가 없다.
        Map<String, Object> noData = parse(callback("callback-ready", pending, jobId));
        analysisOf(noData).put("data", null);
        callbackHandler.handle(JSON.writeValueAsString(noData));
        callbackHandler.handle("not json " + sessionId);

        assertThat(jdbc.queryForList("select reason from unmatched_callback where payload like ? order by callback_id",
                String.class, "%" + sessionId + "%"))
                .containsExactly("UNKNOWN_REQUEST", "ID_MISMATCH", "INVALID_BODY", "INVALID_BODY", "INVALID_BODY", "INVALID_BODY");
        VideoAnalysisEntity untouched = reload(pending);
        assertThat(untouched.getStatus()).isEqualTo(VideoAnalysisStatus.PENDING);
        assertThat(untouched.getResult()).isNull();
    }

    /** PostgreSQL은 NUL을 받지 못한다. 식별자에 섞여 와도 행을 찾기 전에 따로 남겨 5xx 재전송 고리에 빠지지 않는다. */
    @Test
    void 식별자에_NUL이_섞인_본문은_조회하기_전에_따로_남긴다() {
        VideoAnalysisEntity pending = pendingRow();
        Map<String, Object> body = parse(callback("callback-ready", pending, "job-" + UUID.randomUUID()));
        body.put("requestId", pending.getRequestId() + "\u0000");

        assertThat(callbackHandler.handle(JSON.writeValueAsString(body))).isEqualTo(VideoAnalysisCallbackHandler.Outcome.INVALID);

        assertThat(unmatchedCount()).isEqualTo(1);
        assertThat(reload(pending).getStatus()).isEqualTo(VideoAnalysisStatus.PENDING);
    }

    @Test
    void 작업_조회로_받은_결과는_콜백과_같게_저장된다() {
        for (String name : List.of("callback-partial", "callback-unconfigured", "callback-failed")) {
            VideoAnalysisEntity byCallback = pendingRow();
            callbackHandler.handle(callback(name, byCallback, "job-" + UUID.randomUUID()));

            VideoAnalysisEntity byJob = pendingRow("job-" + UUID.randomUUID());
            Map<String, Object> body = parse(callback(name, byJob, byJob.getJobId()));
            when(aiServerClient.getVideoAnalysisJob(byJob.getJobId())).thenReturn(new VideoAnalysisJobResponse(
                    byJob.getJobId(), name.equals("callback-failed") ? "failed" : "completed", body, null));
            service.check(byJob);

            VideoAnalysisEntity expected = reload(byCallback);
            VideoAnalysisEntity actual = reload(byJob);
            assertThat(actual.getStatus()).as(name).isEqualTo(expected.getStatus());
            assertThat(actual.getErrorCode()).as(name).isEqualTo(expected.getErrorCode());
            assertThat(actual.getErrorRetryable()).as(name).isEqualTo(expected.getErrorRetryable());
            assertThat(actual.getResult() == null).as(name).isEqualTo(expected.getResult() == null);
        }
        assertThat(unmatchedCount()).isZero();
    }

    @Test
    void 작업_조회_결과가_계약에_맞지_않으면_콜백처럼_따로_남기고_닫는다() {
        VideoAnalysisEntity byJob = pendingRow("job-" + UUID.randomUUID());
        Map<String, Object> body = parse(callback("callback-unavailable", byJob, byJob.getJobId()));
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) analysisOf(body).get("error");
        error.put("retryable", true);
        when(aiServerClient.getVideoAnalysisJob(byJob.getJobId()))
                .thenReturn(new VideoAnalysisJobResponse(byJob.getJobId(), "completed", body, null));

        service.check(byJob);

        assertThat(reload(byJob).getStatus()).isEqualTo(VideoAnalysisStatus.FAILED);
        assertThat(reload(byJob).getErrorCode()).isEqualTo("INVALID_RESULT");
        assertThat(unmatchedCount()).isEqualTo(1);
    }

    // ---- 멱등성·늦은 결과 ----

    @Test
    void 키_순서만_다른_같은_종료_문서는_중복이라_아무것도_바꾸지_않는다() {
        VideoAnalysisEntity pending = pendingRow();
        String jobId = "job-" + UUID.randomUUID();
        callbackHandler.handle(callback("callback-partial", pending, jobId));
        LocalDateTime updatedAt = reload(pending).getUpdatedAt();

        Object reordered = reversed(parse(callback("callback-partial", pending, jobId)));

        assertThat(callbackHandler.handle(JSON.writeValueAsString(reordered)))
                .isEqualTo(VideoAnalysisCallbackHandler.Outcome.DUPLICATE);
        assertThat(reload(pending).getUpdatedAt()).isEqualTo(updatedAt);
        assertThat(unmatchedCount()).isZero();
    }

    /** 먼저 저장한 결과가 이긴다. ready가 늦게 온 failed로 바뀌지 않고, 늦게 온 문서는 원인을 볼 수 있게 따로 남는다. */
    @Test
    void 상충하는_종료_문서는_처음_것을_두고_따로_남긴다() {
        VideoAnalysisEntity pending = pendingRow();
        String jobId = "job-" + UUID.randomUUID();
        callbackHandler.handle(callback("callback-ready", pending, jobId));

        assertThat(callbackHandler.handle(callback("callback-failed", pending, jobId)))
                .isEqualTo(VideoAnalysisCallbackHandler.Outcome.CONFLICT);

        assertThat(reload(pending).getStatus()).isEqualTo(VideoAnalysisStatus.READY);
        assertThat(jdbc.queryForList("select detail from unmatched_callback where payload like ?",
                String.class, "%" + sessionId + "%")).singleElement().asString().contains("처음 저장한 결과를 유지");
    }

    /** 이 서버가 시간 초과로만 닫은 행에는 분석 서버의 종료 문서가 없다. 늦게 온 첫 결과는 받는다. */
    @Test
    void 결과_없이_닫은_요청에_늦게_온_첫_결과는_저장하고_그다음_다른_문서는_상충이다() {
        VideoAnalysisEntity pending = pendingRow();
        analysisRepository.fail(pending.getAnalysisId(), "PROCESSING_OVERDUE", null, "늦음", LocalDateTime.now());
        String jobId = "job-" + UUID.randomUUID();

        assertThat(callbackHandler.handle(callback("callback-partial", pending, jobId)))
                .isEqualTo(VideoAnalysisCallbackHandler.Outcome.APPLIED);
        assertThat(reload(pending).getStatus()).isEqualTo(VideoAnalysisStatus.PARTIAL);
        assertThat(reload(pending).getErrorCode()).isNull();

        assertThat(callbackHandler.handle(callback("callback-unconfigured", pending, jobId)))
                .isEqualTo(VideoAnalysisCallbackHandler.Outcome.CONFLICT);
        assertThat(reload(pending).getStatus()).isEqualTo(VideoAnalysisStatus.PARTIAL);
    }

    /** 늦게 온 옛 요청의 결과는 그 행에만 남는다. 화면은 새 요청의 상태와 옛 결과를 함께 보여 준다. */
    @Test
    void 옛_요청의_늦은_결과는_자기_행만_바꾸고_새_요청을_가리지_않는다() {
        ReflectionTestUtils.setField(service, "enabled", true);
        InterviewRecordingEntity video = video(interview, LocalDateTime.now().minusHours(1));
        VideoAnalysisEntity old = analysisRepository.save(row(video, "chain-a", VideoAnalysisStatus.PENDING, null));
        analysisRepository.fail(old.getAnalysisId(), "NOT_ACKNOWLEDGED", null, "접수 미확인", LocalDateTime.now());
        VideoAnalysisEntity latest = analysisRepository.save(row(video, "chain-b", VideoAnalysisStatus.PENDING, null));

        callbackHandler.handle(callback("callback-partial", old, "job-" + UUID.randomUUID()));

        assertThat(reload(old).getStatus()).isEqualTo(VideoAnalysisStatus.PARTIAL);
        assertThat(reload(latest).getStatus()).isEqualTo(VideoAnalysisStatus.PENDING);
        assertThat(reload(latest).getResult()).isNull();
        VideoAnalysisResponse view = service.get(interview.getUserId(), interview.getInterviewId());
        assertThat(view.status()).isEqualTo(VideoAnalysisResponse.Status.ANALYZING);
        assertThat(view.latestAnalysisId()).isEqualTo(String.valueOf(latest.getAnalysisId()));
        assertThat(view.behaviorAnalysisId()).isEqualTo(String.valueOf(old.getAnalysisId()));
        assertThat(view.showingPreviousResult()).isTrue();
    }

    @Test
    void 콜백이_먼저_오면_뒤늦은_접수_응답이_작업_id를_바꾸지_않는다() {
        VideoAnalysisEntity pending = pendingRow();
        String jobId = "job-" + UUID.randomUUID();
        callbackHandler.handle(callback("callback-ready", pending, jobId));

        int updated = analysisRepository.acknowledge(pending.getAnalysisId(), "job-other-" + sessionId,
                LocalDateTime.now().plusMinutes(10), LocalDateTime.now());

        assertThat(updated).isZero();
        assertThat(reload(pending).getJobId()).isEqualTo(jobId);
        assertThat(reload(pending).getStatus()).isEqualTo(VideoAnalysisStatus.READY);
    }

    @Test
    void 결과가_먼저_들어오면_실패로_닫는_갱신이_덮지_않는다() {
        VideoAnalysisEntity pending = pendingRow();
        callbackHandler.handle(callback("callback-ready", pending, "job-" + UUID.randomUUID()));

        int updated = analysisRepository.fail(pending.getAnalysisId(), "PROCESSING_OVERDUE", null, "늦음", LocalDateTime.now());

        assertThat(updated).isZero();
        assertThat(reload(pending).getStatus()).isEqualTo(VideoAnalysisStatus.READY);
    }

    // ---- 두 연결의 경쟁 ----

    /**
     * 한 연결이 요청 행을 잠근 채 결과를 저장하는 동안 다른 연결이 상충하는 결과를 보내면, 뒤 연결은 잠금이 풀릴 때까지
     * 기다렸다가 저장된 문서를 보고 상충으로 따로 남긴다. 읽은 값으로 덮어쓰는 마지막 쓰기 승리가 일어나지 않는다.
     */
    @Test
    void 두_연결이_상충하는_결과를_겹쳐_보내도_먼저_잠근_쪽만_반영한다() throws Exception {
        VideoAnalysisEntity pending = pendingRow();
        String jobId = "job-" + UUID.randomUUID();
        String ready = callback("callback-ready", pending, jobId);
        String failed = callback("callback-failed", pending, jobId);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<VideoAnalysisCallbackHandler.Outcome> first = CompletableFuture.supplyAsync(() ->
                transactionTemplate.execute(tx -> {
                    analysisRepository.findLockedByRequestId(pending.getRequestId()).orElseThrow();
                    locked.countDown();
                    await(release);
                    return callbackHandler.handle(ready);
                }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<VideoAnalysisCallbackHandler.Outcome> second =
                CompletableFuture.supplyAsync(() -> callbackHandler.handle(failed));

        // 뒤 연결은 행 잠금에서 기다린다.
        Thread.sleep(500);
        assertThat(second).isNotDone();
        release.countDown();

        assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(VideoAnalysisCallbackHandler.Outcome.APPLIED);
        assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(VideoAnalysisCallbackHandler.Outcome.CONFLICT);
        assertThat(reload(pending).getStatus()).isEqualTo(VideoAnalysisStatus.READY);
        assertThat(unmatchedCount()).isEqualTo(1);
    }

    /**
     * 한 탭의 재분석이 면접 행을 잠근 채 사용자 묶음을 하나 더 만드는 동안 다른 탭이 누르면, 뒤 탭은 잠금이 풀린 뒤 늘어난
     * 사용자 재분석 수를 보고 한도로 거절한다. 만든 행이 진행 중(PENDING)이 아니어도 막는다 — 진행 중 요청 하나라는 DB
     * 인덱스로는 잡히지 않는 경쟁이다.
     */
    @Test
    void 재분석_한도는_면접_잠금_아래에서_다시_세어_인덱스가_못_잡는_경쟁도_막는다() throws Exception {
        ReflectionTestUtils.setField(service, "enabled", true);
        InterviewRecordingEntity video = video(interview, LocalDateTime.now().minusHours(1));
        VideoAnalysisEntity firstUser = row(video, "chain-u1", VideoAnalysisStatus.FAILED, null);
        firstUser.setRequestedBy(VideoAnalysisRequestedBy.USER);
        analysisRepository.save(firstUser);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<Void> otherTab = CompletableFuture.runAsync(() -> transactionTemplate.executeWithoutResult(tx -> {
            interviewRepository.lockById(interview.getInterviewId()).orElseThrow();
            // 다른 탭이 두 번째 사용자 묶음을 만들었고, 그 요청은 곧바로 결과 없이 닫혔다.
            VideoAnalysisEntity secondUser = row(video, "chain-u2", VideoAnalysisStatus.FAILED, null);
            secondUser.setRequestedBy(VideoAnalysisRequestedBy.USER);
            analysisRepository.save(secondUser);
            locked.countDown();
            await(release);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<VideoAnalysisResponse> thisTab =
                CompletableFuture.supplyAsync(() -> service.retry(interview.getUserId(), interview.getInterviewId()));

        Thread.sleep(500);
        assertThat(thisTab).isNotDone();
        release.countDown();
        otherTab.get(10, TimeUnit.SECONDS);

        assertThatThrownBy(() -> thisTab.get(10, TimeUnit.SECONDS))
                .hasCauseInstanceOf(BusinessException.class)
                .hasMessageContaining("2번까지");
        assertThat(analysisRepository.findAllByRecordingIdOrderByAnalysisIdAsc(video.getRecordingId()))
                .extracting(VideoAnalysisEntity::getChainId).containsExactly("chain-u1", "chain-u2");
    }

    @Test
    void 두_탭이_동시에_재분석을_눌러도_한_번만_받는다() throws Exception {
        ReflectionTestUtils.setField(service, "enabled", true);
        InterviewRecordingEntity video = video(interview, LocalDateTime.now().minusHours(1));
        analysisRepository.save(row(video, "chain-a", VideoAnalysisStatus.PARTIAL, null));
        CyclicBarrier together = new CyclicBarrier(2);

        List<CompletableFuture<Object>> tabs = List.of(1, 2).stream()
                .map(i -> CompletableFuture.supplyAsync(() -> {
                    await(together);
                    try {
                        return (Object) service.retry(interview.getUserId(), interview.getInterviewId());
                    } catch (BusinessException e) {
                        return e;
                    }
                }))
                .toList();
        List<Object> results = new ArrayList<>();
        for (CompletableFuture<Object> tab : tabs) {
            results.add(tab.get(20, TimeUnit.SECONDS));
        }

        assertThat(results).filteredOn(VideoAnalysisResponse.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(BusinessException.class::isInstance).singleElement()
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(analysisRepository.findAllByRecordingIdOrderByAnalysisIdAsc(video.getRecordingId()))
                .filteredOn(analysis -> analysis.getRequestedBy() == VideoAnalysisRequestedBy.USER).hasSize(1);
    }

    // ---- 면접 자격 ----

    /** 중단 면접의 늦은 결과는 받되(2xx) 반영하지 않고, 기다리던 요청은 정책 사유로 닫는다. */
    @Test
    void 중단된_면접의_늦은_콜백은_반영하지_않고_대기_요청을_닫는다() {
        VideoAnalysisEntity pending = pendingRow();
        jdbc.update("update interview set status = 'ABANDONED' where interview_id = ?", interview.getInterviewId());

        assertThat(callbackHandler.handle(callback("callback-ready", pending, "job-" + UUID.randomUUID())))
                .isEqualTo(VideoAnalysisCallbackHandler.Outcome.POLICY_CLOSED);

        VideoAnalysisEntity stored = reload(pending);
        assertThat(stored.getStatus()).isEqualTo(VideoAnalysisStatus.FAILED);
        assertThat(stored.getErrorCode()).isEqualTo("INTERVIEW_ABANDONED");
        assertThat(stored.getErrorRetryable()).isFalse();
        assertThat(stored.getResult()).isNull();
        assertThat(unmatchedCount()).isZero();
    }

    @Test
    void 진행_중인_면접의_콜백은_반영하지도_닫지도_않는다() {
        VideoAnalysisEntity pending = pendingRow();
        jdbc.update("update interview set status = 'IN_PROGRESS' where interview_id = ?", interview.getInterviewId());

        assertThat(callbackHandler.handle(callback("callback-ready", pending, "job-" + UUID.randomUUID())))
                .isEqualTo(VideoAnalysisCallbackHandler.Outcome.POLICY_HELD);

        assertThat(reload(pending).getStatus()).isEqualTo(VideoAnalysisStatus.PENDING);
        assertThat(reload(pending).getResult()).isNull();
    }

    // ---- 기능 스위치 ----

    /** 꺼도 맡긴 요청은 같은 요청 id로 작업을 조회해 결과를 되찾는다. 새 요청 행은 만들지 않는다. */
    @Test
    void 꺼져_있어도_맡긴_요청은_작업_조회로_복구하고_새_요청은_만들지_않는다() {
        VideoAnalysisEntity pending = pendingRow("job-" + UUID.randomUUID());
        jdbc.update("update video_analysis set next_check_at = ? where analysis_id = ?",
                LocalDateTime.now().minusMinutes(1), pending.getAnalysisId());
        video(interview, LocalDateTime.now().minusMinutes(10));
        Map<String, Object> body = parse(callback("callback-partial", pending, pending.getJobId()));
        when(aiServerClient.getVideoAnalysisJob(pending.getJobId()))
                .thenReturn(new VideoAnalysisJobResponse(pending.getJobId(), "completed", body, null));
        ReflectionTestUtils.setField(service, "videoApiToken", "video-secret");

        service.sweep();

        assertThat(reload(pending).getStatus()).isEqualTo(VideoAnalysisStatus.PARTIAL);
        assertThat(jdbc.queryForObject("select count(*) from video_analysis where interview_id = ?", Integer.class,
                interview.getInterviewId())).isEqualTo(1);
        verify(aiServerClient, never()).requestVideoAnalysis(any());
    }

    // ---- 준비 ----

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

    /** 계약 예제의 식별자를 이 요청의 값으로 바꾼다. 행동 데이터의 videoId도 같이 바꾼다. 나머지는 그대로다. */
    private static String callback(String fixture, VideoAnalysisEntity analysis, String jobId) {
        return callback(fixture(fixture), analysis, jobId);
    }

    private static String callback(Map<String, Object> body, VideoAnalysisEntity analysis, String jobId) {
        body.put("jobId", jobId);
        body.put("requestId", analysis.getRequestId());
        body.put("sessionId", analysis.getSessionId());
        body.put("interviewId", String.valueOf(analysis.getInterviewId()));
        body.put("userId", String.valueOf(analysis.getUserId()));
        if (body.get("result") instanceof Map<?, ?>) {
            result(body).put("videoId", String.valueOf(analysis.getRecordingId()));
            if (analysisOf(body).get("data") instanceof Map<?, ?> data) {
                @SuppressWarnings("unchecked")
                Map<String, Object> behavior = (Map<String, Object>) data;
                behavior.put("videoId", String.valueOf(analysis.getRecordingId()));
            }
        }
        return JSON.writeValueAsString(body);
    }

    private static Map<String, Object> fixture(String name) {
        try (InputStream in = VideoAnalysisPersistenceTest.class.getResourceAsStream("/contracts/behavior/" + name + ".json")) {
            return JSON.readValue(in, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> parse(String body) {
        return JSON.readValue(body, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> result(Map<String, Object> body) {
        return (Map<String, Object>) body.get("result");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> analysisOf(Map<String, Object> body) {
        return (Map<String, Object>) result(body).get("analysis");
    }

    /** 객체의 키 순서를 모두 뒤집는다. 배열의 순서는 그대로 둔다. */
    private static Object reversed(Object value) {
        if (value instanceof Map<?, ?> map) {
            List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
            Map<Object, Object> out = new LinkedHashMap<>();
            for (int i = entries.size() - 1; i >= 0; i--) {
                out.put(entries.get(i).getKey(), reversed(entries.get(i).getValue()));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(VideoAnalysisPersistenceTest::reversed).toList();
        }
        return value;
    }

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

    private InterviewEntity otherInterview(Status status, AnswerMode answerMode) {
        InterviewEntity other = interviewRepository.save(InterviewEntity.builder()
                .userId(77L).sessionId("s-" + UUID.randomUUID()).status(status).answerMode(answerMode).build());
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

    private int unmatchedCount() {
        return jdbc.queryForObject("select count(*) from unmatched_callback where payload like ?",
                Integer.class, "%" + sessionId + "%");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
