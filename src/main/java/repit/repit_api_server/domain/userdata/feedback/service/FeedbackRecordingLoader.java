package repit.repit_api_server.domain.userdata.feedback.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import repit.repit_api_server.domain.userdata.answer.entity.AnswerEntity;
import repit.repit_api_server.domain.userdata.feedback.dto.request.FeedbackInterviewVideo;
import repit.repit_api_server.domain.userdata.feedback.dto.request.FeedbackRecording;
import repit.repit_api_server.domain.userdata.question.entity.QuestionEntity;
import repit.repit_api_server.domain.userdata.recording.entity.InterviewRecordingEntity;
import repit.repit_api_server.domain.userdata.recording.entity.enums.RecordingKind;
import repit.repit_api_server.domain.userdata.recording.repository.InterviewRecordingRepository;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 채점 요청에 실을 면접 파일을 모은다.
 *
 * <p>웹은 두 가지를 올린다. 질문에 답할 때마다 그 답변의 음성을 하나씩, 그리고 면접을 멈출 때 면접
 * 화면 전체를 담은 영상을 하나. 앞엣것은 질문·답변에 이어 붙이고, 뒤엣것은 면접 하나에 하나뿐이라
 * 요청에 따로 싣는다.
 *
 * <p>답변 음성을 잇는 기준은 채팅 서버 질문 번호다. 웹은 면접 중에 받은 그 번호를 파일에 붙여 올리고,
 * 채팅 서버는 면접을 마칠 때 같은 번호를 질문 id로 넘긴다(둘 다 채팅 세션의 ChatQuestion.questionId).
 * 우리 질문 PK는 파일이 올라올 때 아직 없고 기록이 다시 오면 바뀌므로, 채점 요청을 만드는 이 시점에
 * 번호를 PK로 옮긴다.
 *
 * <ul>
 *   <li>질문 하나에 파일 하나. 같은 질문에 여러 번 올라왔으면 가장 나중 것이 그 답변의 파일이다.</li>
 *   <li>기록에 없는 질문 번호의 파일은 어느 답변에도 붙일 수 없어 싣지 않는다.</li>
 *   <li>질문 진행 순서대로 싣는다. 분석 서버가 questions와 같은 순서로 읽을 수 있다.</li>
 * </ul>
 *
 * <p>파일은 공개 버킷에 두지 않으므로 분석 서버가 잠깐 내려받을 수 있는 서명 주소로 넘긴다.
 */
@Component
@RequiredArgsConstructor
public class FeedbackRecordingLoader {

    private static final Logger log = LoggerFactory.getLogger(FeedbackRecordingLoader.class);

    private final InterviewRecordingRepository recordingRepository;
    private final S3Presigner s3Presigner;

    @Value("${spring.cloud.aws.s3.bucket}")
    private String bucketName;

    // 분석 서버가 파일을 내려받을 수 있는 시간. 접수 뒤 작업이 밀려도 만료되지 않을 만큼 둔다.
    @Value("${app.feedback.recording-url-ttl:6h}")
    private Duration recordingUrlTtl;

    /**
     * 질문 진행 순서대로 정리한 답변 음성.
     *
     * @param questions 채점 요청에 실리는 질문, 진행 순서대로
     * @param answers   채점 요청에 실리는 답변
     */
    public List<FeedbackRecording> load(Long interviewId, List<QuestionEntity> questions, List<AnswerEntity> answers) {
        Map<Long, InterviewRecordingEntity> latestByChatId = new HashMap<>();
        List<InterviewRecordingEntity> unmatched = new ArrayList<>();
        Set<Long> knownChatIds = questions.stream()
                .map(QuestionEntity::getChatQuestionId)
                .collect(Collectors.toSet());

        // 올라온 순서대로 읽으므로 뒤에 온 파일이 앞의 것을 덮는다.
        List<InterviewRecordingEntity> recordings =
                recordingRepository.findAllByInterviewIdAndKindOrderByRecordingIdAsc(interviewId, RecordingKind.ANSWER);
        for (InterviewRecordingEntity recording : recordings) {
            Long chatId = recording.getChatQuestionId();
            if (chatId == null || !knownChatIds.contains(chatId)) {
                unmatched.add(recording);
                continue;
            }
            latestByChatId.put(chatId, recording);
        }
        if (!unmatched.isEmpty()) {
            log.warn("질문에 이을 수 없는 답변 파일을 채점에서 뺍니다. interviewId={}, recordingIds={}",
                    interviewId, unmatched.stream().map(InterviewRecordingEntity::getRecordingId).toList());
        }

        Map<Long, Long> answerIdByQuestionId = answers.stream()
                .collect(Collectors.toMap(AnswerEntity::getQuestionId, AnswerEntity::getAnswerId, (first, later) -> later));

        List<FeedbackRecording> loaded = new ArrayList<>();
        for (QuestionEntity question : questions) {
            InterviewRecordingEntity recording = latestByChatId.get(question.getChatQuestionId());
            if (recording != null) {
                loaded.add(toFeedbackRecording(recording, question.getQuestionId(),
                        answerIdByQuestionId.get(question.getQuestionId())));
            }
        }
        return loaded;
    }

    /** 면접 화면 전체를 담은 영상. 올라오지 않았으면 null이고, 그래도 채점은 한다. */
    public FeedbackInterviewVideo loadInterviewVideo(Long interviewId) {
        return recordingRepository
                .findFirstByInterviewIdAndKindOrderByRecordingIdDesc(interviewId, RecordingKind.FULL_INTERVIEW)
                .map(recording -> FeedbackInterviewVideo.builder()
                        .recordingId(String.valueOf(recording.getRecordingId()))
                        .videoUrl(presign(recording.getS3Key()))
                        .contentType(recording.getContentType())
                        .fileSize(recording.getFileSize())
                        .uploadedAt(toUtc(recording.getCreatedAt()))
                        .build())
                .orElse(null);
    }

    private FeedbackRecording toFeedbackRecording(InterviewRecordingEntity recording, Long questionId, Long answerId) {
        return FeedbackRecording.builder()
                .recordingId(String.valueOf(recording.getRecordingId()))
                .questionId(String.valueOf(questionId))
                .answerId(answerId == null ? null : String.valueOf(answerId))
                .fileUrl(presign(recording.getS3Key()))
                .contentType(recording.getContentType())
                .fileSize(recording.getFileSize())
                .uploadedAt(toUtc(recording.getCreatedAt()))
                .build();
    }

    private String presign(String key) {
        GetObjectPresignRequest request = GetObjectPresignRequest.builder()
                .signatureDuration(recordingUrlTtl)
                .getObjectRequest(get -> get.bucket(bucketName).key(key))
                .build();
        return s3Presigner.presignGetObject(request).url().toString();
    }

    private OffsetDateTime toUtc(LocalDateTime time) {
        return time == null ? null : time.atOffset(ZoneOffset.UTC);
    }
}
