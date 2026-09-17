package repit.repit_api_server.domain.userdata.feedback.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import repit.repit_api_server.domain.userdata.feedback.dto.request.FeedbackRecording;
import repit.repit_api_server.domain.userdata.question.entity.QuestionEntity;
import repit.repit_api_server.domain.userdata.recording.entity.InterviewRecordingEntity;
import repit.repit_api_server.domain.userdata.recording.repository.InterviewRecordingRepository;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 채점 요청에 실을 답변 영상을 모은다.
 *
 * <p>영상은 공개 버킷에 두지 않으므로 분석 서버가 잠깐 내려받을 수 있는 서명 주소로 넘긴다.
 * 웹은 영상에 채팅 서버 질문 번호를 붙여 보냈고 채점 요청의 질문은 우리 PK로 나가므로, 같은
 * 요청 안에서 이어 읽을 수 있게 번호를 옮긴다.
 */
@Component
@RequiredArgsConstructor
public class FeedbackRecordingLoader {

    private static final String MP4_CONTENT_TYPE = "video/mp4";

    private final InterviewRecordingRepository recordingRepository;
    private final S3Presigner s3Presigner;

    @Value("${spring.cloud.aws.s3.bucket}")
    private String bucketName;

    // 분석 서버가 영상을 내려받을 수 있는 시간. 접수 뒤 작업이 밀려도 만료되지 않을 만큼 둔다.
    @Value("${app.feedback.recording-url-ttl:6h}")
    private Duration recordingUrlTtl;

    /** @param questions 채점 요청에 실리는 질문. 이 안에 없는 질문을 가리키는 영상은 질문 id를 비운다. */
    public List<FeedbackRecording> load(Long interviewId, List<QuestionEntity> questions) {
        Map<Long, Long> questionIdByChatId = questions.stream()
                .filter(question -> question.getChatQuestionId() != null)
                .collect(Collectors.toMap(QuestionEntity::getChatQuestionId, QuestionEntity::getQuestionId));

        return recordingRepository.findAllByInterviewIdOrderByRecordingIdAsc(interviewId).stream()
                .map(recording -> toFeedbackRecording(recording, questionIdByChatId))
                .toList();
    }

    private FeedbackRecording toFeedbackRecording(InterviewRecordingEntity recording, Map<Long, Long> questionIdByChatId) {
        Long questionId = recording.getChatQuestionId() == null
                ? null
                : questionIdByChatId.get(recording.getChatQuestionId());
        return FeedbackRecording.builder()
                .recordingId(String.valueOf(recording.getRecordingId()))
                .questionId(questionId == null ? null : String.valueOf(questionId))
                .videoUrl(presign(recording.getS3Key()))
                .contentType(MP4_CONTENT_TYPE)
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
