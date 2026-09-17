package repit.repit_api_server.domain.userdata.recording.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import repit.repit_api_server.domain.userdata.answer.entity.AnswerEntity;
import repit.repit_api_server.domain.userdata.answer.repository.AnswerRepository;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.question.entity.QuestionEntity;
import repit.repit_api_server.domain.userdata.question.repository.QuestionRepository;
import repit.repit_api_server.domain.userdata.recording.dto.request.RecordingAnalysisRequest;
import repit.repit_api_server.domain.userdata.recording.dto.response.RecordingAnalysisAcceptedResponse;
import repit.repit_api_server.domain.userdata.recording.entity.InterviewRecordingEntity;
import repit.repit_api_server.domain.userdata.recording.entity.RecordingAnalysisEntity;
import repit.repit_api_server.domain.userdata.recording.entity.enums.RecordingAnalysisStatus;
import repit.repit_api_server.domain.userdata.recording.repository.InterviewRecordingRepository;
import repit.repit_api_server.domain.userdata.recording.repository.RecordingAnalysisRepository;
import repit.repit_api_server.global.client.AiServerClient;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 면접 녹화를 분석 서버로 넘긴다. 질문·답변과 답변 영상이 모두 모인 뒤 면접당 한 번 보낸다.
 *
 * <p>질문·답변은 채팅 서버가 면접을 마칠 때, 영상은 웹이 답변을 마칠 때마다 따로 들어온다.
 * 어느 쪽이 먼저 올지 정해져 있지 않아서 두 입구 모두에서 "다 모였는지"를 본다.
 *
 * <ul>
 *   <li>다 모였다 = 기록이 저장됐고, 답한 질문마다 영상이 있다. 그러면 곧바로 보낸다.</li>
 *   <li>텍스트로 답한 질문은 영상이 영영 오지 않는다. 그래서 마지막으로 무언가 들어온 뒤
 *       유예 시간이 지나면 주기 스윕이 있는 영상만으로 보낸다. 영상이 하나도 없으면 보내지 않는다.</li>
 * </ul>
 *
 * <p>분석 서버 호출은 트랜잭션 밖에서 한다. 느린 HTTP 응답을 기다리는 동안 DB 커넥션을 붙잡지 않는다.
 */
@Service
@RequiredArgsConstructor
public class RecordingAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(RecordingAnalysisService.class);

    static final String CALLBACK_PATH = "/api/interviews/recordings/callback";
    private static final String MP4_CONTENT_TYPE = "video/mp4";

    private final RecordingAnalysisRepository analysisRepository;
    private final InterviewRecordingRepository recordingRepository;
    private final InterviewRepository interviewRepository;
    private final QuestionRepository questionRepository;
    private final AnswerRepository answerRepository;
    private final AiServerClient aiServerClient;
    private final S3Presigner s3Presigner;

    @Value("${spring.cloud.aws.s3.bucket}")
    private String bucketName;

    @Value("${app.callback-base-url}")
    private String callbackBaseUrl;

    // 기록이 저장된 뒤 영상이 덜 모였을 때, 마지막으로 무언가 들어오고 이만큼 조용하면 있는 것만 보낸다.
    @Value("${app.recording-analysis.upload-grace:2m}")
    private Duration uploadGrace;

    // 분석 서버가 영상을 내려받을 수 있는 시간. 접수 뒤 작업이 밀려도 만료되지 않을 만큼 둔다.
    @Value("${app.recording-analysis.video-url-ttl:6h}")
    private Duration videoUrlTtl;

    /** 채팅 서버가 넘긴 질문·답변이 저장된 뒤 부른다. 같은 면접의 기록이 여러 번 와도 한 번만 보낸다. */
    public void onTranscriptSaved(Long interviewId) {
        RecordingAnalysisEntity analysis = analysisRepository.findByInterviewId(interviewId).orElse(null);
        if (analysis == null) {
            analysis = createWaiting(interviewId);
        } else {
            analysisRepository.touchIfWaiting(interviewId, LocalDateTime.now());
        }
        sendIfComplete(analysis);
    }

    /** 웹이 영상을 하나 올린 뒤 부른다. 기록이 아직 오지 않았으면 기록이 올 때 함께 본다. */
    public void onRecordingUploaded(Long interviewId) {
        RecordingAnalysisEntity analysis = analysisRepository.findByInterviewId(interviewId).orElse(null);
        if (analysis == null) {
            return;
        }
        if (analysisRepository.touchIfWaiting(interviewId, LocalDateTime.now()) == 0) {
            // 이미 보냈거나 보내지 않기로 한 뒤에 도착한 영상이다. 분석에는 들어가지 않는다.
            log.warn("녹화 분석을 이미 처리한 면접에 영상이 늦게 올라왔습니다. interviewId={}, status={}",
                    interviewId, analysis.getStatus());
            return;
        }
        sendIfComplete(analysis);
    }

    /**
     * 영상이 덜 모인 채 조용해진 면접을 보낸다.
     *
     * <p>한 건이 넘어져도 나머지는 계속 보낸다. 실패한 건 하나 때문에 스윕이 멈추면 그 뒤 면접이 모두 매달린다.
     */
    @Scheduled(fixedDelayString = "${app.recording-analysis.sweep-interval:30s}")
    public void sweepQuietInterviews() {
        List<RecordingAnalysisEntity> quiet = analysisRepository.findAllByStatusAndLastActivityAtBefore(
                RecordingAnalysisStatus.WAITING, LocalDateTime.now().minus(uploadGrace));

        for (RecordingAnalysisEntity analysis : quiet) {
            try {
                send(analysis.getAnalysisId());
            } catch (RuntimeException e) {
                log.error("녹화 분석을 보내지 못했습니다. analysisId={}, interviewId={}",
                        analysis.getAnalysisId(), analysis.getInterviewId(), e);
            }
        }
    }

    private RecordingAnalysisEntity createWaiting(Long interviewId) {
        try {
            return analysisRepository.save(RecordingAnalysisEntity.builder()
                    .interviewId(interviewId)
                    .status(RecordingAnalysisStatus.WAITING)
                    .lastActivityAt(LocalDateTime.now())
                    .build());
        } catch (DataIntegrityViolationException e) {
            // 같은 면접의 기록이 동시에 두 번 들어와 다른 쪽이 먼저 만들었다.
            return analysisRepository.findByInterviewId(interviewId).orElseThrow(() -> e);
        }
    }

    private void sendIfComplete(RecordingAnalysisEntity analysis) {
        if (analysis.getStatus() != RecordingAnalysisStatus.WAITING) {
            return;
        }
        if (hasRecordingForEveryAnswer(analysis.getInterviewId())) {
            send(analysis.getAnalysisId());
        }
    }

    /**
     * 답한 질문마다 영상이 있는지.
     *
     * <p>웹은 영상에 채팅 서버 질문 번호를 붙여 보내고, 답변은 우리 질문 PK를 가리킨다. 그래서 질문을
     * 거쳐 같은 번호 체계로 맞춘 뒤 견준다. 답변이 하나도 없으면 무엇을 기다려야 할지 모르므로 스윕에 맡긴다.
     */
    private boolean hasRecordingForEveryAnswer(Long interviewId) {
        Map<Long, Long> chatIdByQuestionId = questionRepository.findAllByInterviewId(interviewId).stream()
                .filter(question -> question.getChatQuestionId() != null)
                .collect(Collectors.toMap(QuestionEntity::getQuestionId, QuestionEntity::getChatQuestionId));

        Set<Long> answeredChatIds = answerRepository.findAllByInterviewId(interviewId).stream()
                .map(answer -> chatIdByQuestionId.get(answer.getQuestionId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (answeredChatIds.isEmpty()) {
            return false;
        }

        Set<Long> recordedChatIds = recordingRepository.findAllByInterviewIdOrderByRecordingIdAsc(interviewId).stream()
                .map(InterviewRecordingEntity::getChatQuestionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return recordedChatIds.containsAll(answeredChatIds);
    }

    private void send(Long analysisId) {
        if (analysisRepository.claim(analysisId) == 0) {
            return;
        }
        RecordingAnalysisEntity analysis = analysisRepository.findById(analysisId).orElseThrow();
        Long interviewId = analysis.getInterviewId();

        List<InterviewRecordingEntity> recordings = recordingRepository.findAllByInterviewIdOrderByRecordingIdAsc(interviewId);
        if (recordings.isEmpty()) {
            analysis.markSkipped();
            analysisRepository.save(analysis);
            log.info("영상이 하나도 오지 않아 녹화 분석을 보내지 않습니다. interviewId={}", interviewId);
            return;
        }

        try {
            RecordingAnalysisAcceptedResponse accepted = aiServerClient.requestRecordingAnalysis(
                    toRequest(interviewId, recordings));
            analysis.markPending(accepted == null ? null : accepted.getJobId());
            log.info("녹화 분석을 접수했습니다. interviewId={}, recordings={}, jobId={}",
                    interviewId, recordings.size(), analysis.getJobId());
        } catch (RuntimeException e) {
            // 분석 서버 장애가 업로드 응답이나 면접 기록 저장까지 실패로 만들면 안 된다. 사유만 남긴다.
            analysis.markFailed(e.getMessage());
            log.error("녹화 분석 접수에 실패했습니다. interviewId={}", interviewId, e);
        }
        analysisRepository.save(analysis);
    }

    private RecordingAnalysisRequest toRequest(Long interviewId, List<InterviewRecordingEntity> recordings) {
        InterviewEntity interview = interviewRepository.findById(interviewId)
                .orElseThrow(() -> new IllegalStateException("면접을 찾을 수 없습니다. interviewId=" + interviewId));
        List<QuestionEntity> questions = questionRepository.findAllByInterviewIdOrderByQuestionIdAsc(interviewId);
        List<AnswerEntity> answers = answerRepository.findAllByInterviewIdOrderByAnswerIdAsc(interviewId);

        Map<Long, Long> questionIdByChatId = questions.stream()
                .filter(question -> question.getChatQuestionId() != null)
                .collect(Collectors.toMap(QuestionEntity::getChatQuestionId, QuestionEntity::getQuestionId));

        return RecordingAnalysisRequest.builder()
                .sessionId(interview.getSessionId())
                .interviewId(String.valueOf(interview.getInterviewId()))
                .userId(String.valueOf(interview.getUserId()))
                .mode(interview.getMode() == null ? null : interview.getMode().name())
                .callbackUrl(callbackBaseUrl + CALLBACK_PATH)
                .questions(questions.stream()
                        .map(question -> RecordingAnalysisRequest.Question.builder()
                                .questionId(String.valueOf(question.getQuestionId()))
                                .parentId(question.getParentId() == null ? null : String.valueOf(question.getParentId()))
                                .type(question.getType())
                                .intention(question.getIntention())
                                .content(question.getContent())
                                .build())
                        .toList())
                .answers(answers.stream()
                        .map(answer -> RecordingAnalysisRequest.Answer.builder()
                                .answerId(String.valueOf(answer.getAnswerId()))
                                .questionId(String.valueOf(answer.getQuestionId()))
                                .content(answer.getContent())
                                .responseTime(answer.getResponseTime())
                                .createdAt(toUtc(answer.getCreatedAt()))
                                .build())
                        .toList())
                .recordings(recordings.stream()
                        .map(toRecording(questionIdByChatId))
                        .toList())
                .build();
    }

    private Function<InterviewRecordingEntity, RecordingAnalysisRequest.Recording> toRecording(
            Map<Long, Long> questionIdByChatId) {
        return recording -> {
            Long questionId = recording.getChatQuestionId() == null
                    ? null
                    : questionIdByChatId.get(recording.getChatQuestionId());
            return RecordingAnalysisRequest.Recording.builder()
                    .recordingId(String.valueOf(recording.getRecordingId()))
                    .questionId(questionId == null ? null : String.valueOf(questionId))
                    .videoUrl(presign(recording.getS3Key()))
                    .contentType(MP4_CONTENT_TYPE)
                    .fileSize(recording.getFileSize())
                    .uploadedAt(toUtc(recording.getCreatedAt()))
                    .build();
        };
    }

    private String presign(String key) {
        GetObjectPresignRequest request = GetObjectPresignRequest.builder()
                .signatureDuration(videoUrlTtl)
                .getObjectRequest(get -> get.bucket(bucketName).key(key))
                .build();
        return s3Presigner.presignGetObject(request).url().toString();
    }

    private OffsetDateTime toUtc(LocalDateTime time) {
        return time == null ? null : time.atOffset(ZoneOffset.UTC);
    }
}
