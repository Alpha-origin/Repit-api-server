package repit.repit_api_server.domain.userdata.analysis.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import repit.repit_api_server.domain.userdata.analysis.dto.request.AudioAnalysisCallbackRequest;
import repit.repit_api_server.domain.userdata.analysis.dto.request.AudioAnalysisRequest;
import repit.repit_api_server.domain.userdata.analysis.dto.response.AudioAnalysisAcceptedResponse;
import repit.repit_api_server.domain.userdata.analysis.dto.response.AudioAnalysisJobResponse;
import repit.repit_api_server.domain.userdata.analysis.dto.response.AudioAnalysisResponse;
import repit.repit_api_server.domain.userdata.analysis.dto.response.AudioAnalysisResponse.Status;
import repit.repit_api_server.domain.userdata.analysis.entity.AudioAnalysisEntity;
import repit.repit_api_server.domain.userdata.analysis.entity.AudioAnalysisResultEntity;
import repit.repit_api_server.domain.userdata.analysis.entity.enums.AudioAnalysisStatus;
import repit.repit_api_server.domain.userdata.analysis.repository.AudioAnalysisRepository;
import repit.repit_api_server.domain.userdata.analysis.repository.AudioAnalysisResultRepository;
import repit.repit_api_server.domain.userdata.analysis.service.AudioRecordingLoader.AnswerRecording;
import repit.repit_api_server.domain.userdata.answer.entity.AnswerEntity;
import repit.repit_api_server.domain.userdata.answer.repository.AnswerRepository;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackDispatchEntity;
import repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackDispatchKind;
import repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackDispatchStatus;
import repit.repit_api_server.domain.userdata.feedback.repository.FeedbackDispatchRepository;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.question.entity.QuestionEntity;
import repit.repit_api_server.domain.userdata.question.repository.QuestionRepository;
import repit.repit_api_server.domain.userdata.recording.entity.InterviewRecordingEntity;
import repit.repit_api_server.domain.userdata.recording.entity.enums.RecordingEndReason;
import repit.repit_api_server.global.client.AiServerClient;
import repit.repit_api_server.global.exception.BusinessException;
import repit.repit_api_server.global.exception.ExternalApiException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 답변 음성을 분석 서버의 음성 분석(POST /analysis/audio)에 맡기고, 콜백으로 온 결과를 남겨 웹 조회에 내려준다.
 *
 * <p>채점과는 따로 돈다. 채점은 질문·답변만 있으면 되지만 음성 분석은 답변 음성이 모여야 해서,
 * 언제 보낼지는 {@code FeedbackDispatchService}가 정하고 이 서비스는 보내는 일만 한다.
 *
 * <p>요청 id는 보내기 전에 남긴다. 분석 서버는 같은 {@code sessionId}·{@code requestId}·내용을 다시 받으면
 * 새 작업을 만들지 않고 기존 작업을 202로 돌려주므로, 응답을 받지 못한 요청은 <b>같은 요청 id로</b> 다시 보내야
 * 같은 답변이 두 번 분석되지 않는다. 반대로 분석 서버가 거절한 요청은 그 자리에서 닫아, 그 녹음이 새 요청
 * id로 다시 나가게 한다 — 서명이 만료된 주소는 새 주소와 새 요청 id로만 살릴 수 있다.
 *
 * <p>사용자는 면접당 {@value #MAX_USER_RETRIES}번 다시 요청할 수 있다({@link #retry}). 다시 보내는 것은 결과가 없거나
 * 다시 하면 나아질 실패가 있는 답변뿐이다({@link #worthRetrying}).
 */
@Service
@RequiredArgsConstructor
public class AudioAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AudioAnalysisService.class);

    private static final String CALLBACK_PATH = "/api/analyses/audio/callback";
    // 분석 서버가 한 요청에 받는 녹음 수의 상한.
    static final int MAX_RECORDINGS_PER_REQUEST = 12;
    // 분석 서버가 받는 녹음 하나의 크기 상한. 넘는 것이 하나라도 실리면 요청 전체가 422다.
    static final long MAX_SOURCE_BYTES = 100_000_000L;
    // 사용자가 다시 요청할 수 있는 횟수. 영상의 영상당 2회와 같다.
    static final int MAX_USER_RETRIES = 2;
    private static final String TIMED_OUT = "음성 분석 결과를 제때 받지 못했습니다.";
    private static final String NOT_ACKNOWLEDGED = "음성 분석 접수를 확인하지 못해 새 요청으로 다시 맡깁니다.";

    private final AudioAnalysisRepository analysisRepository;
    private final AudioAnalysisResultRepository resultRepository;
    private final InterviewRepository interviewRepository;
    private final QuestionRepository questionRepository;
    private final AnswerRepository answerRepository;
    private final FeedbackDispatchRepository dispatchRepository;
    private final AudioRecordingLoader recordingLoader;
    private final AiServerClient aiServerClient;
    private final ObjectMapper objectMapper;

    @Value("${app.callback-base-url}")
    private String callbackBaseUrl;

    // 이 시간을 넘도록 콜백이 오지 않으면 작업을 직접 조회해 결과를 찾아본다.
    @Value("${app.audio-analysis.pending-timeout:10m}")
    private Duration pendingTimeout;

    // 이 시간까지 결과를 찾지 못하면 그 요청을 닫는다. 닫으면 그 녹음이 새 요청 id로 다시 나가므로,
    // 조회가 잠깐 실패한 것과 결과가 정말 없는 것을 가릴 만큼 넉넉해야 한다. 짧으면 살아 있는 작업을
    // 버리고 같은 답변을 두 번 분석하게 된다.
    @Value("${app.audio-analysis.give-up-after:1h}")
    private Duration giveUpAfter;

    /**
     * 면접의 답변 음성을 음성 분석에 맡긴다.
     *
     * <p>순서는 이렇다 — 먼저 앞서 보낸 요청들을 정리하고(접수를 확인하지 못한 것은 같은 요청 id로 다시 보내고,
     * 결과를 기다리다 시간을 넘긴 것은 작업을 조회해 결과를 가져온다), 그러고도 아직 맡기지 않은 녹음을 12개씩
     * 나눠 보낸다.
     *
     * <p>외부 서버를 부르므로 트랜잭션으로 감싸지 않는다. 느린 응답을 기다리는 내내 DB 커넥션을 붙잡게 된다.
     *
     * <p>중간에 실패하면 예외를 그대로 올린다. 앞서 접수된 요청은 이미 기록돼 있어, 다시 시도할 때 남은 것만 나간다.
     */
    public void requestForFinishedInterview(Long interviewId) {
        InterviewEntity interview = interviewRepository.findById(interviewId)
                .orElseThrow(() -> BusinessException.notFound("면접을 찾을 수 없습니다"));
        String callbackUrl = callbackUrl();
        if (interview.getSessionId() == null || interview.getSessionId().isBlank()) {
            // 분석 서버는 세션 id를 요청 id와 함께 중복 판단 키로 쓴다. 비워 보내면 요청 전체가 422다.
            throw BusinessException.unprocessable("면접에 채팅 세션이 없어 음성 분석을 맡길 수 없습니다.");
        }

        Map<Long, AnswerRecording> analyzable = analyzable(interviewId);
        Set<Long> alreadyRequested = settleEarlierRequests(interview, analyzable, callbackUrl);

        List<AnswerRecording> pending = analyzable.values().stream()
                .filter(loaded -> !alreadyRequested.contains(loaded.recording().getRecordingId()))
                .toList();
        if (pending.isEmpty()) {
            log.info("음성 분석에 맡길 답변 음성이 없습니다. interviewId={}, 이미 맡긴 녹음={}건",
                    interviewId, alreadyRequested.size());
            return;
        }
        if (analyzable.size() > MAX_RECORDINGS_PER_REQUEST) {
            // 분석 서버는 한 면접에 녹음 12개까지를 전제한다. 나눠 보내면 요청은 받아들여지지만 작업이 갈린다.
            log.warn("한 면접의 답변 음성이 {}개로 한 요청 한도({})를 넘어 나눠 보냅니다. interviewId={}",
                    analyzable.size(), MAX_RECORDINGS_PER_REQUEST, interviewId);
        }

        for (int from = 0; from < pending.size(); from += MAX_RECORDINGS_PER_REQUEST) {
            List<AnswerRecording> chunk =
                    pending.subList(from, Math.min(from + MAX_RECORDINGS_PER_REQUEST, pending.size()));
            sendAsNewRequest(interview, chunk, callbackUrl);
        }
    }

    /**
     * 웹이 보는 음성 분석 상태와 답변별 지표.
     *
     * <ul>
     *   <li>답변과 녹음은 요청을 만들 때와 같은 규칙({@link #analyzable})으로 잇는다. 요청에 실릴 수 없는 녹음을 담으면
     *       영영 PENDING으로 남아 웹이 조회를 멈추지 못한다.</li>
     *   <li>질문·답변 id는 저장된 결과의 값이 아니라 지금 값을 쓴다. 채팅 서버가 면접 기록을 다시 보내면 PK가 바뀌지만
     *       녹음의 채팅 질문 번호는 그대로다.</li>
     *   <li>결과는 녹음 id로 찾고, 여럿이면 요청 순서(analysis_id)로 가장 나중 것을 쓴다. 도착 순서로 고르면 늦게 온 옛
     *       결과가 새 결과를 가린다.</li>
     *   <li>결과 없는 녹음이 앞으로 요청될지는 음성 분석 대기 행으로 정한다({@link #toBeRequested}).</li>
     *   <li>결과보다 나중 요청이 그 녹음을 다시 분석하는 중이면 진행 중으로 보인다({@link #reanalyzing}).</li>
     * </ul>
     */
    public AudioAnalysisResponse get(Long userId, Long interviewId) {
        InterviewEntity interview = ownedInterview(userId, interviewId);
        FeedbackDispatchEntity dispatch = audioDispatch(interviewId);
        Status toBeRequested = toBeRequested(dispatch);
        List<AudioAnalysisResponse.Answer> answers = answers(interviewId, toBeRequested);
        if (answers.isEmpty()) {
            // 음성이 아직 올라오는 중일 수 있다. 요청할 때가 지났는데도 없으면 받을 결과가 없다(텍스트 면접 등).
            return new AudioAnalysisResponse(toBeRequested == Status.PENDING ? Status.PENDING : Status.UNAVAILABLE,
                    List.of(), false);
        }
        return new AudioAnalysisResponse(overallStatus(answers), answers,
                retryBlockedReason(interview, dispatch, answers) == null);
    }

    /**
     * 사용자가 음성 분석을 다시 요청한다.
     *
     * <p>{@link #worthRetrying} 답변의 녹음만 새 요청 id로 요청 자리를 만들고, 음성 분석 대기 행을 기다리는 자리로
     * 돌린다. 보내는 것은 대기 스윕이 한다 — 요청 경로가 작업 id 없는 요청을 그 요청 id로 보내므로 따로 보내는 길을
     * 두지 않는다. 서명 URL도 그때 새로 만든다.
     *
     * <p>둘을 한 트랜잭션으로 묶는다. 대기 행만 돌면 횟수만 쓰고 아무것도 보내지 않고, 요청 자리만 남으면 아무도
     * 보내지 않는다.
     */
    @Transactional
    public AudioAnalysisResponse retry(Long userId, Long interviewId) {
        InterviewEntity interview = ownedInterview(userId, interviewId);
        FeedbackDispatchEntity dispatch = audioDispatch(interviewId);
        List<AudioAnalysisResponse.Answer> answers = answers(interviewId, toBeRequested(dispatch));
        String blocked = retryBlockedReason(interview, dispatch, answers);
        if (blocked != null) {
            throw BusinessException.conflict(blocked);
        }
        if (dispatchRepository.reopenForUserRetry(interviewId, FeedbackDispatchKind.AUDIO_ANALYSIS,
                MAX_USER_RETRIES) == 0) {
            // 연달아 누른 다른 요청이 먼저 받아들여졌다.
            throw BusinessException.conflict("이미 분석 중입니다.");
        }

        List<Long> recordingIds = answers.stream()
                .filter(AudioAnalysisService::worthRetrying)
                .map(answer -> Long.valueOf(answer.recordingId()))
                .toList();
        for (int from = 0; from < recordingIds.size(); from += MAX_RECORDINGS_PER_REQUEST) {
            analysisRepository.save(newRequest(interview,
                    recordingIds.subList(from, Math.min(from + MAX_RECORDINGS_PER_REQUEST, recordingIds.size()))));
        }
        log.info("사용자가 음성 분석을 다시 요청했습니다. interviewId={}, recordings={}", interviewId, recordingIds);
        return get(userId, interviewId);
    }

    /**
     * 다시 요청을 받을 수 없는 이유. 받을 수 있으면 null.
     *
     * <p>정상 결과도 말더듬 검출이 없어 늘 PARTIAL이라, 상태만으로는 열지 않고 다시 보낼 답변이 있을 때만 연다.
     */
    String retryBlockedReason(InterviewEntity interview, FeedbackDispatchEntity dispatch,
                              List<AudioAnalysisResponse.Answer> answers) {
        if (dispatch == null) {
            return "아직 분석을 시작하지 않았습니다.";
        }
        if (dispatch.getStatus() == FeedbackDispatchStatus.WAITING || dispatch.getStatus() == FeedbackDispatchStatus.SENDING
                || answers.stream().anyMatch(answer -> answer.status() == Status.PENDING)) {
            return "이미 분석 중입니다.";
        }
        if (interview.getSessionId() == null || interview.getSessionId().isBlank()) {
            return "면접에 채팅 세션이 없어 음성 분석을 할 수 없습니다.";
        }
        if (answers.stream().noneMatch(AudioAnalysisService::worthRetrying)) {
            return "다시 분석할 답변이 없습니다.";
        }
        if (dispatch.getUserRetryCount() >= MAX_USER_RETRIES) {
            return "음성 분석은 면접당 " + MAX_USER_RETRIES + "번까지 다시 요청할 수 있습니다.";
        }
        return null;
    }

    /**
     * 다시 보내면 결과가 나아질 수 있는 답변.
     *
     * <ul>
     *   <li>결과가 없다(지표도 실패 사유도 없다) — 요청이 결과 없이 닫혔거나, 끝난 요청에 이 녹음의 결과만 빠졌거나,
     *       요청을 마친 뒤 올라왔다.</li>
     *   <li>파일·timing·fluency 중 실패한 곳을 분석 서버가 retryable=true로 알렸다 — 원본 다운로드 실패, 서명 만료,
     *       외부 서비스 장애 등.</li>
     * </ul>
     * 라인의 partial(말더듬 검출 미제공, 무발화·한글 없음)과 retryable=false인 실패(전사할 말 없음, 파일 손상 등)는
     * 같은 음성이면 같은 결과가 나오므로 넣지 않는다.
     */
    static boolean worthRetrying(AudioAnalysisResponse.Answer answer) {
        if (answer.status() == Status.PENDING) {
            return false;
        }
        if (answer.timing() == null && answer.fluency() == null && answer.error() == null) {
            return true;
        }
        return (answer.error() != null && Boolean.TRUE.equals(answer.error().retryable()))
                || retryableLine(answer.timing()) || retryableLine(answer.fluency());
    }

    private static boolean retryableLine(Map<String, Object> line) {
        return line != null && Boolean.TRUE.equals(errorField(line.get("error"), "retryable", Boolean.class));
    }

    private InterviewEntity ownedInterview(Long userId, Long interviewId) {
        InterviewEntity interview = interviewRepository.findById(interviewId)
                .orElseThrow(() -> BusinessException.notFound("면접을 찾을 수 없습니다"));
        if (!Objects.equals(userId, interview.getUserId())) {
            throw BusinessException.forbidden("본인의 면접만 볼 수 있습니다.");
        }
        return interview;
    }

    private FeedbackDispatchEntity audioDispatch(Long interviewId) {
        return dispatchRepository.findByInterviewIdAndKind(interviewId, FeedbackDispatchKind.AUDIO_ANALYSIS)
                .orElse(null);
    }

    /** 녹음이 있는 답변마다 지금 보여 줄 상태와 지표. */
    private List<AudioAnalysisResponse.Answer> answers(Long interviewId, Status toBeRequested) {
        Collection<AnswerRecording> loaded = analyzable(interviewId).values();
        if (loaded.isEmpty()) {
            return List.of();
        }

        // 요청 순서대로 덮어 쓰므로 녹음마다 가장 나중 요청·결과가 남는다.
        List<AudioAnalysisEntity> requests = analysisRepository.findAllByInterviewIdOrderByAnalysisIdAsc(interviewId);
        Map<Long, AudioAnalysisEntity> requestById = new HashMap<>();
        Map<Long, AudioAnalysisEntity> requestByRecording = new HashMap<>();
        for (AudioAnalysisEntity request : requests) {
            requestById.put(request.getAnalysisId(), request);
            for (Long recordingId : Objects.requireNonNullElse(request.getRecordingIds(), List.<Long>of())) {
                requestByRecording.put(recordingId, request);
            }
        }
        Map<Long, AudioAnalysisResultEntity> resultByRecording = new HashMap<>();
        if (!requests.isEmpty()) {
            resultRepository.findAllByAnalysisIdIn(requestById.keySet())
                    .stream()
                    .filter(result -> result.getRecordingId() != null)
                    .sorted(Comparator.comparing(AudioAnalysisResultEntity::getAnalysisId))
                    .forEach(result -> resultByRecording.put(result.getRecordingId(), result));
        }

        return loaded.stream()
                .map(one -> {
                    Long recordingId = one.recording().getRecordingId();
                    AudioAnalysisResultEntity result = resultByRecording.get(recordingId);
                    AudioAnalysisEntity latestRequest = requestByRecording.get(recordingId);
                    return result == null || reanalyzing(latestRequest, result, toBeRequested)
                            ? withoutResult(one, latestRequest, toBeRequested)
                            : withResult(one, result, requestById.get(result.getAnalysisId()));
                })
                .toList();
    }

    /**
     * 결과를 낸 요청보다 나중 요청이 그 녹음을 다시 분석하는 중인지. 그동안은 앞선 결과 대신 진행 중으로 보여,
     * 웹이 조회를 멈추지 않고 새 결과까지 받게 한다.
     */
    private static boolean reanalyzing(AudioAnalysisEntity latestRequest, AudioAnalysisResultEntity result,
                                       Status toBeRequested) {
        return latestRequest != null && latestRequest.getAnalysisId() > result.getAnalysisId()
                && statusWithoutResult(latestRequest, toBeRequested) == Status.PENDING;
    }

    private AudioAnalysisResponse.Answer withResult(AnswerRecording loaded, AudioAnalysisResultEntity result,
                                                    AudioAnalysisEntity request) {
        return new AudioAnalysisResponse.Answer(String.valueOf(loaded.questionId()), String.valueOf(loaded.answerId()),
                String.valueOf(loaded.recording().getRecordingId()), request == null ? null : request.getRequestId(),
                resultStatus(result.getStatus()), result.getDurationMs(), result.getTiming(), result.getFluency(),
                errorOf(result.getError()));
    }

    /**
     * 결과 행이 없는 녹음. 그 녹음을 실은 가장 나중 요청을 따른다.
     *
     * <p>요청에 실리지 않았거나 실패로 닫힌 요청에만 실렸으면 다음 요청을 기다리는 것이다 — 닫힌 요청의 녹음은 다음
     * 요청에 다시 실린다. 그때는 다음 요청이 나갈지({@code toBeRequested})를 따른다.
     */
    private static AudioAnalysisResponse.Answer withoutResult(AnswerRecording loaded, AudioAnalysisEntity latestRequest,
                                                              Status toBeRequested) {
        return new AudioAnalysisResponse.Answer(String.valueOf(loaded.questionId()), String.valueOf(loaded.answerId()),
                String.valueOf(loaded.recording().getRecordingId()),
                latestRequest == null ? null : latestRequest.getRequestId(),
                statusWithoutResult(latestRequest, toBeRequested), null, null, null, null);
    }

    private static Status statusWithoutResult(AudioAnalysisEntity latestRequest, Status toBeRequested) {
        return latestRequest == null ? toBeRequested : switch (latestRequest.getStatus()) {
            case PENDING -> Status.PENDING;
            case FAILED -> toBeRequested == Status.PENDING ? Status.PENDING : Status.FAILED;
            // 끝난 요청인데 이 녹음의 결과만 빠졌다. 다시 요청되지 않으므로 받을 결과가 없다.
            case READY, PARTIAL, UNAVAILABLE -> Status.UNAVAILABLE;
        };
    }

    /**
     * 아직 맡기지 않은 녹음이 앞으로 요청될지. 음성 분석 대기 행이 정한다.
     *
     * <ul>
     *   <li>행이 없다 — 면접 기록이 아직 오지 않았다. 기록이 오면 행이 생기고 요청이 나간다.</li>
     *   <li>WAITING·SENDING — 음성이 모이기를 기다리거나 요청하는 중이다(다시 요청을 받은 뒤 포함).</li>
     *   <li>DONE — 요청을 마쳤다. 그 뒤에 올라온 녹음은 이번 분석에 들지 않는다.</li>
     *   <li>FAILED — 요청을 포기했다.</li>
     * </ul>
     */
    private static Status toBeRequested(FeedbackDispatchEntity dispatch) {
        if (dispatch == null) {
            return Status.PENDING;
        }
        return switch (dispatch.getStatus()) {
            case WAITING, SENDING -> Status.PENDING;
            case DONE -> Status.UNAVAILABLE;
            case FAILED -> Status.FAILED;
        };
    }

    /** 결과 행의 status(대문자로 저장). 모르는 값은 받을 수 있는 결과가 없는 것으로 본다 — 콜백 상태와 같은 규칙이다. */
    private static Status resultStatus(String status) {
        if ("READY".equals(status)) {
            return Status.READY;
        }
        return "PARTIAL".equals(status) ? Status.PARTIAL : Status.UNAVAILABLE;
    }

    /** 답변별 상태를 모은 전체 상태. 하나라도 진행 중이면 진행 중이다 — 웹은 PENDING일 때만 다시 조회한다. */
    static Status overallStatus(List<AudioAnalysisResponse.Answer> answers) {
        Set<Status> statuses = answers.stream()
                .map(AudioAnalysisResponse.Answer::status)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Status.class)));
        if (statuses.contains(Status.PENDING)) {
            return Status.PENDING;
        }
        if (statuses.equals(EnumSet.of(Status.READY))) {
            return Status.READY;
        }
        if (statuses.equals(EnumSet.of(Status.FAILED))) {
            return Status.FAILED;
        }
        if (EnumSet.of(Status.UNAVAILABLE, Status.FAILED).containsAll(statuses)) {
            return Status.UNAVAILABLE;
        }
        return Status.PARTIAL;
    }

    /** 저장된 실패 사유({@link #errorText})를 되돌린다. JSON 객체였으면 그 값으로, 아니면 글을 message에 담는다. */
    private AudioAnalysisResponse.Error errorOf(String text) {
        if (text == null) {
            return null;
        }
        Object parsed;
        try {
            parsed = objectMapper.readValue(text, Object.class);
        } catch (JacksonException e) {
            parsed = null;
        }
        if (!(parsed instanceof Map<?, ?> error)) {
            return new AudioAnalysisResponse.Error(null, text, null);
        }
        return new AudioAnalysisResponse.Error(errorField(error, "code", String.class),
                errorField(error, "message", String.class), errorField(error, "retryable", Boolean.class));
    }

    /**
     * 분석 서버에 실을 수 있는 답변 음성. 녹음 id로 찾을 수 있게 담아 돌려준다.
     *
     * <p>크기 상한을 넘는 녹음은 뺀다. 하나라도 실리면 그 요청에 함께 실린 나머지 녹음까지 422로 거절된다.
     *
     * <p>녹음 id나 답변 id가 겹치는 것도 뺀다. 한 요청 안에서 겹치면 역시 거절된다.
     */
    private Map<Long, AnswerRecording> analyzable(Long interviewId) {
        List<QuestionEntity> questions = questionRepository.findAllByInterviewIdOrderByQuestionIdAsc(interviewId);
        List<AnswerEntity> answers = answerRepository.findAllByInterviewIdOrderByAnswerIdAsc(interviewId);

        Map<Long, AnswerRecording> byRecordingId = new LinkedHashMap<>();
        Set<Long> seenAnswerIds = new HashSet<>();
        for (AnswerRecording loaded : recordingLoader.load(interviewId, questions, answers)) {
            InterviewRecordingEntity recording = loaded.recording();
            if (recording.getFileSize() == null || recording.getFileSize() <= 0
                    || recording.getFileSize() > MAX_SOURCE_BYTES) {
                log.warn("크기가 분석 한도를 벗어난 답변 음성을 음성 분석에서 뺍니다. interviewId={}, recordingId={}, fileSize={}",
                        interviewId, recording.getRecordingId(), recording.getFileSize());
                continue;
            }
            if (!seenAnswerIds.add(loaded.answerId())) {
                log.warn("답변 하나에 음성이 둘이라 뒤엣것을 음성 분석에서 뺍니다. interviewId={}, answerId={}, recordingId={}",
                        interviewId, loaded.answerId(), recording.getRecordingId());
                continue;
            }
            byRecordingId.put(recording.getRecordingId(), loaded);
        }
        return byRecordingId;
    }

    /**
     * 앞서 보낸 요청들을 정리하고, 이미 맡겨 둔 녹음을 돌려준다.
     *
     * <ul>
     *   <li>접수(jobId)를 확인하지 못한 요청 — 같은 요청 id로 다시 보낸다. 분석 서버가 이미 받았다면 기존 작업을
     *       돌려주므로 두 번 분석되지 않는다.</li>
     *   <li>결과를 기다리다 확인할 때가 된 요청 — 작업을 직접 조회해 결과가 있으면 가져온다. 콜백 재전송이 모두
     *       실패해도 결과는 분석 서버에 남아 있다.</li>
     *   <li>포기 시간까지 어느 쪽도 되지 않은 요청 — 닫아서 그 녹음이 새 요청 id로 다시 나가게 한다.</li>
     *   <li>녹음을 실은 가장 나중 요청이 닫혔으면(FAILED) 아직 맡기지 않은 것으로 본다. 앞선 결과가 있어도
     *       사용자가 다시 요청한 요청이 닫히면 그 녹음은 다시 나간다.</li>
     * </ul>
     */
    private Set<Long> settleEarlierRequests(InterviewEntity interview, Map<Long, AnswerRecording> analyzable,
                                            String callbackUrl) {
        Map<Long, AudioAnalysisStatus> latestByRecording = new HashMap<>();
        for (AudioAnalysisEntity analysis : analysisRepository
                .findAllByInterviewIdOrderByAnalysisIdAsc(interview.getInterviewId())) {
            if (analysis.getStatus() == AudioAnalysisStatus.PENDING) {
                if (analysis.getJobId() == null) {
                    confirmAcceptance(interview, analysis, analyzable, callbackUrl);
                } else if (timedOut(analysis)) {
                    recoverFromJob(analysis);
                }
            }
            for (Long recordingId : Objects.requireNonNullElse(analysis.getRecordingIds(), List.<Long>of())) {
                latestByRecording.put(recordingId, analysis.getStatus());
            }
        }
        return latestByRecording.entrySet().stream()
                .filter(latest -> latest.getValue() != AudioAnalysisStatus.FAILED)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    /**
     * 접수를 확인하지 못한 요청을 같은 요청 id로 다시 보낸다. 사용자가 다시 요청해 아직 보내지 않은 요청도 여기서
     * 처음 나간다.
     *
     * <p>포기 시간이 지나기 전에는 닫지 않는다. 202를 받지 못한 요청도 분석 서버는 받아 두었을 수 있어서,
     * 일찍 닫고 새 요청 id로 보내면 같은 답변이 두 번 분석된다. 같은 요청 id로 다시 보내는 것은 안전하다.
     *
     * <p>실을 녹음을 되짚지 못하면 같은 내용을 만들 수 없어 그때는 닫는다.
     */
    private void confirmAcceptance(InterviewEntity interview, AudioAnalysisEntity analysis,
                                   Map<Long, AnswerRecording> analyzable, String callbackUrl) {
        List<AnswerRecording> chunk = new ArrayList<>();
        for (Long recordingId : Objects.requireNonNullElse(analysis.getRecordingIds(), List.<Long>of())) {
            AnswerRecording loaded = analyzable.get(recordingId);
            if (loaded == null) {
                // 그 사이 면접 기록이 다시 저장돼 질문·답변 id가 바뀌었다. 같은 요청 id로는 같은 내용을 만들 수 없다.
                markFailed(analysis, NOT_ACKNOWLEDGED);
                return;
            }
            chunk.add(loaded);
        }
        if (chunk.isEmpty()) {
            markFailed(analysis, NOT_ACKNOWLEDGED);
            return;
        }
        if (givenUp(analysis)) {
            markFailed(analysis, NOT_ACKNOWLEDGED);
            return;
        }
        log.info("접수를 확인하지 못한 음성 분석 요청을 그 요청 id로 보냅니다. interviewId={}, requestId={}",
                interview.getInterviewId(), analysis.getRequestId());
        send(interview, analysis, chunk, callbackUrl, true);
    }

    /**
     * 콜백을 끝내 받지 못한 작업의 결과를 직접 가져온다.
     *
     * <p>분석 서버는 콜백을 여섯 번까지 보내고 그래도 실패하면 포기하지만 결과는 남겨 둔다.
     *
     * <p>조회가 실패했거나 아직 결과가 없으면 기다리는 채로 둔다. 조회 실패는 분석 서버가 잠깐 답하지 못한
     * 것일 수 있고, 그것을 "결과가 없다"로 보고 닫으면 살아 있는 작업을 버린 채 같은 답변을 다시 맡기게 된다.
     * 포기 시간이 지나서야 닫는다 — 그러면 그 녹음이 새 요청 id로 다시 나간다.
     */
    private void recoverFromJob(AudioAnalysisEntity analysis) {
        AudioAnalysisJobResponse job = null;
        try {
            job = aiServerClient.getAudioAnalysisJob(analysis.getJobId());
        } catch (RuntimeException e) {
            log.warn("콜백이 오지 않은 음성 분석 작업을 조회하지 못해 다음에 다시 봅니다. analysisId={}, jobId={}",
                    analysis.getAnalysisId(), analysis.getJobId(), e);
        }
        if (job != null && job.getResult() != null) {
            log.info("콜백을 받지 못한 음성 분석 결과를 작업 조회로 가져왔습니다. analysisId={}, jobId={}",
                    analysis.getAnalysisId(), analysis.getJobId());
            applyResult(analysis, job.getResult());
            return;
        }
        if (!givenUp(analysis)) {
            log.info("음성 분석 결과가 아직 없어 기다립니다. analysisId={}, jobId={}, 작업={}",
                    analysis.getAnalysisId(), analysis.getJobId(), job == null ? "조회 실패" : job.getStatus());
            return;
        }
        log.warn("음성 분석 결과를 {} 안에 받지 못해 실패 처리합니다. analysisId={}, jobId={}",
                giveUpAfter, analysis.getAnalysisId(), analysis.getJobId());
        markFailed(analysis, TIMED_OUT);
    }

    /** 콜백을 더 기다리지 말고 작업을 직접 확인해 볼 때가 됐는지. */
    private boolean timedOut(AudioAnalysisEntity analysis) {
        return olderThan(analysis, pendingTimeout);
    }

    /** 결과를 찾지 못한 채 오래돼, 닫고 새 요청으로 다시 맡길 때가 됐는지. */
    private boolean givenUp(AudioAnalysisEntity analysis) {
        return olderThan(analysis, giveUpAfter);
    }

    private static boolean olderThan(AudioAnalysisEntity analysis, Duration limit) {
        return analysis.getCreatedAt() != null
                && !analysis.getCreatedAt().plus(limit).isAfter(LocalDateTime.now());
    }

    private void markFailed(AudioAnalysisEntity analysis, String reason) {
        analysis.setStatus(AudioAnalysisStatus.FAILED);
        analysis.setErrorMessage(reason);
        analysisRepository.save(analysis);
    }

    /** 새 요청 id로 접수 기록을 먼저 남기고 보낸다. 보낸 뒤에 남기면 응답을 못 받은 요청을 되짚을 수 없다. */
    private void sendAsNewRequest(InterviewEntity interview, List<AnswerRecording> chunk, String callbackUrl) {
        AudioAnalysisEntity analysis = analysisRepository.save(newRequest(interview, recordingIds(chunk)));
        send(interview, analysis, chunk, callbackUrl, true);
    }

    /** 새 요청 id로 만든, 아직 보내지 않은 요청. */
    private static AudioAnalysisEntity newRequest(InterviewEntity interview, List<Long> recordingIds) {
        return AudioAnalysisEntity.builder()
                .interviewId(interview.getInterviewId())
                .userId(interview.getUserId())
                .sessionId(interview.getSessionId())
                .requestId("audio-" + UUID.randomUUID())
                .status(AudioAnalysisStatus.PENDING)
                .recordingIds(recordingIds)
                .build();
    }

    private static List<Long> recordingIds(List<AnswerRecording> chunk) {
        return chunk.stream().map(loaded -> loaded.recording().getRecordingId()).toList();
    }

    /**
     * 요청을 보내고 접수 결과를 남긴다.
     *
     * <p>분석 서버가 상태 코드로 답한 실패는 그 요청이 받아들여지지 않은 것이므로 그 자리에서 닫는다. 닫지 않으면
     * 그 녹음이 "이미 맡긴 것"으로 남아 영영 분석되지 않는다.
     *
     * <p>409는 같은 요청 id로 다른 내용을 보냈다는 뜻이다(분석 서버 정책이 바뀐 뒤에도 그렇다). 그때는 새 요청
     * id로 한 번 다시 보낸다.
     *
     * <p>답을 받지 못한 실패는 접수됐는지 알 수 없으므로 열어 둔다. 다음 시도가 같은 요청 id로 다시 보내 확인한다.
     */
    private void send(InterviewEntity interview, AudioAnalysisEntity analysis, List<AnswerRecording> chunk,
                      String callbackUrl, boolean mayUseNewRequestId) {
        AudioAnalysisRequest request = AudioAnalysisRequest.builder()
                .requestId(analysis.getRequestId())
                .sessionId(interview.getSessionId())
                .interviewId(String.valueOf(interview.getInterviewId()))
                .userId(String.valueOf(interview.getUserId()))
                .callbackUrl(callbackUrl)
                .recordings(chunk.stream().map(this::toRecording).toList())
                .build();

        AudioAnalysisAcceptedResponse accepted;
        try {
            accepted = aiServerClient.requestAudioAnalysis(request);
        } catch (ExternalApiException e) {
            HttpStatusCode status = e.getStatusCode();
            if (status == null) {
                // 보냈지만 답을 못 받았다. 접수됐을 수 있으니 기록을 열어 두고 다음에 같은 요청 id로 확인한다.
                throw e;
            }
            markFailed(analysis, describe(e));
            if (status.value() == HttpStatus.CONFLICT.value() && mayUseNewRequestId) {
                log.warn("같은 요청 id로 다른 내용을 보낸 것으로 거절당해 새 요청 id로 다시 보냅니다. requestId={}",
                        analysis.getRequestId(), e);
                AudioAnalysisEntity retry = analysisRepository.save(newRequest(interview, recordingIds(chunk)));
                send(interview, retry, chunk, callbackUrl, false);
                return;
            }
            throw e;
        }

        if (analysis.getJobId() == null) {
            analysis.setJobId(accepted == null ? null : accepted.getJobId());
        }
        analysisRepository.save(analysis);
        log.info("답변 음성을 음성 분석에 맡겼습니다. interviewId={}, requestId={}, jobId={}, recordings={}",
                interview.getInterviewId(), analysis.getRequestId(), analysis.getJobId(), analysis.getRecordingIds());
    }

    private AudioAnalysisRequest.Recording toRecording(AnswerRecording loaded) {
        InterviewRecordingEntity recording = loaded.recording();
        RecordingEndReason endReason = recording.getEndReason() == null
                ? RecordingEndReason.UNKNOWN
                : recording.getEndReason();
        return AudioAnalysisRequest.Recording.builder()
                .recordingId(String.valueOf(recording.getRecordingId()))
                .questionId(String.valueOf(loaded.questionId()))
                .answerId(String.valueOf(loaded.answerId()))
                .fileUrl(recordingLoader.presign(recording.getS3Key()))
                .contentType(audioContentType(recording))
                .fileSize(recording.getFileSize())
                .uploadedAt(toUtc(recording.getCreatedAt()))
                .endReason(endReason.wireName())
                .build();
    }

    /**
     * 분석 서버에 넘길 형식 이름.
     *
     * <p>{@code audio/<subtype>}만 받는다. 파라미터가 붙거나({@code audio/webm;codecs=opus}) {@code video/}로
     * 시작하면 요청 전체가 422다. 지금은 답변 파일을 audio로만 저장하지만, 그 규칙이 생기기 전에 올라온 행에는
     * {@code video/mp4}가 남아 있어 여기서 바꿔 보낸다.
     */
    private String audioContentType(InterviewRecordingEntity recording) {
        String contentType = recording.getContentType();
        if (contentType == null || contentType.isBlank()) {
            return "audio/mpeg";
        }
        String trimmed = contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        if (trimmed.startsWith("video/")) {
            String converted = "audio/" + trimmed.substring("video/".length());
            log.warn("영상 형식으로 기록된 답변 음성을 {}로 바꿔 보냅니다. recordingId={}, 기록={}",
                    converted, recording.getRecordingId(), contentType);
            return converted;
        }
        return trimmed.startsWith("audio/") ? trimmed : "audio/" + trimmed;
    }

    private String callbackUrl() {
        return CallbackUrls.require(callbackBaseUrl, CALLBACK_PATH);
    }

    /**
     * 분석 서버가 음성 분석을 마치고 보내는 콜백.
     *
     * <p>한 요청에 콜백은 한 번이지만 재전송이 있어 같은 결과가 여섯 번까지 올 수 있고, 순서도 보장되지 않는다.
     * 요청 id로 그 요청의 기록을 찾아 그 자리만 덮으므로, 앞선 요청의 결과가 뒤의 결과를 덮지 않는다.
     *
     * <p>어긋난 값은 거절하지 않고 맞춰 넣는다. 여기서 예외를 던지면 분석 서버가 결과를 버리고, 그 결과는
     * 되찾을 수 없다.
     */
    @Transactional
    public void handleCallback(AudioAnalysisCallbackRequest request) {
        AudioAnalysisEntity analysis = findTarget(request);
        if (analysis == null) {
            log.warn("알 수 없는 음성 분석 콜백을 받았습니다. requestId={}, jobId={}, interviewId={}",
                    request.getRequestId(), request.getJobId(), request.getInterviewId());
            return;
        }
        if (request.getInterviewId() != null
                && !request.getInterviewId().equals(String.valueOf(analysis.getInterviewId()))) {
            log.warn("음성 분석 콜백의 면접이 요청과 다릅니다. 요청 기준으로 저장합니다. analysisId={}, 요청 면접={}, 받은 면접={}",
                    analysis.getAnalysisId(), analysis.getInterviewId(), request.getInterviewId());
        }
        applyResult(analysis, request);
    }

    /** 콜백과 작업 조회가 같은 결과를 주므로 저장도 한 길로 모은다. */
    private void applyResult(AudioAnalysisEntity analysis, AudioAnalysisCallbackRequest result) {
        if (analysis.getJobId() == null) {
            analysis.setJobId(result.getJobId());
        }

        AudioAnalysisStatus status = AudioAnalysisStatus.fromCallback(result.getStatus());
        if (status == null) {
            log.warn("음성 분석 결과의 상태를 알 수 없어 받을 수 있는 결과가 없는 것으로 봅니다. analysisId={}, status={}",
                    analysis.getAnalysisId(), result.getStatus());
            status = AudioAnalysisStatus.UNAVAILABLE;
        }
        analysis.setStatus(status);
        analysis.setErrorMessage(null);

        List<AudioAnalysisResultEntity> rows = new ArrayList<>();
        for (AudioAnalysisCallbackRequest.Result one : Objects.requireNonNullElse(result.getResults(),
                List.<AudioAnalysisCallbackRequest.Result>of())) {
            rows.add(toResultRow(analysis.getAnalysisId(), one));
        }

        // 콜백이 재전송되어도 결과가 겹치지 않도록 지우고 다시 넣는다.
        resultRepository.deleteAllByAnalysisId(analysis.getAnalysisId());
        resultRepository.saveAll(rows);
        analysisRepository.save(analysis);
    }

    private AudioAnalysisEntity findTarget(AudioAnalysisCallbackRequest request) {
        if (request.getRequestId() != null) {
            AudioAnalysisEntity byRequestId = analysisRepository.findByRequestId(request.getRequestId()).orElse(null);
            if (byRequestId != null) {
                return byRequestId;
            }
        }
        if (request.getJobId() != null) {
            AudioAnalysisEntity byJobId = analysisRepository.findByJobId(request.getJobId()).orElse(null);
            if (byJobId != null) {
                return byJobId;
            }
        }
        return createFromCallback(request);
    }

    /**
     * 접수 기록이 없는 콜백을 받아낸다.
     *
     * <p>요청 id는 보내기 전에 남기므로 정상적인 흐름에서는 기록이 이미 있다. 기록을 남긴 트랜잭션이 되돌려진
     * 뒤에 접수가 살아 있는 경우처럼 드문 어긋남에서만 여기까지 온다. 그래도 결과를 버리지는 않는다 — 분석은
     * 실제로 끝났고, 지우면 되찾을 수 없다.
     */
    private AudioAnalysisEntity createFromCallback(AudioAnalysisCallbackRequest request) {
        Long interviewId = parseId(request.getInterviewId());
        if (request.getRequestId() == null || interviewId == null) {
            return null;
        }
        InterviewEntity interview = interviewRepository.findById(interviewId).orElse(null);
        if (interview == null) {
            return null;
        }
        List<Long> recordingIds = Objects.requireNonNullElse(request.getResults(),
                        List.<AudioAnalysisCallbackRequest.Result>of()).stream()
                .map(result -> parseId(result.getRecordingId()))
                .filter(Objects::nonNull)
                .toList();
        return analysisRepository.save(AudioAnalysisEntity.builder()
                .interviewId(interview.getInterviewId())
                .userId(interview.getUserId())
                .sessionId(interview.getSessionId())
                .requestId(request.getRequestId())
                .jobId(request.getJobId())
                .status(AudioAnalysisStatus.PENDING)
                .recordingIds(recordingIds)
                .build());
    }

    private AudioAnalysisResultEntity toResultRow(Long analysisId, AudioAnalysisCallbackRequest.Result result) {
        Object error = result.getError();
        return AudioAnalysisResultEntity.builder()
                .analysisId(analysisId)
                .recordingId(parseId(result.getRecordingId()))
                .questionId(parseId(result.getQuestionId()))
                .answerId(parseId(result.getAnswerId()))
                .status(result.getStatus() == null ? null : result.getStatus().trim().toUpperCase(Locale.ROOT))
                .durationMs(result.getDurationMs())
                .timing(result.getTiming())
                .fluency(result.getFluency())
                .error(errorText(error))
                .errorCode(errorField(error, "code", String.class))
                .errorRetryable(errorField(error, "retryable", Boolean.class))
                .build();
    }

    /**
     * 실패 사유에서 분기에 쓰는 값만 꺼낸다. 표에 없는 코드도 오므로 아는 값으로 좁히지 않고 그대로 둔다.
     */
    private static <T> T errorField(Object error, String field, Class<T> type) {
        if (!(error instanceof Map<?, ?> map)) {
            return null;
        }
        Object value = map.get(field);
        return type.isInstance(value) ? type.cast(value) : null;
    }

    /** 실패 사유를 글로 남긴다. 문자열이면 그대로, 객체면 JSON으로. */
    private String errorText(Object error) {
        if (error == null) {
            return null;
        }
        if (error instanceof String text) {
            return text;
        }
        try {
            return objectMapper.writeValueAsString(error);
        } catch (JacksonException e) {
            return String.valueOf(error);
        }
    }

    private static String describe(RuntimeException e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static Long parseId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 두 서버 모두 JVM 기본 시간대가 UTC라 저장된 시각을 그대로 UTC로 읽는다. */
    private static OffsetDateTime toUtc(LocalDateTime time) {
        return time == null ? null : time.atOffset(ZoneOffset.UTC);
    }
}
