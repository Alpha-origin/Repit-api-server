package repit.repit_api_server.domain.userdata.recording.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import repit.repit_api_server.domain.userdata.feedback.service.FeedbackDispatchService;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.recording.dto.response.InterviewRecordingResponse;
import repit.repit_api_server.domain.userdata.recording.entity.InterviewRecordingEntity;
import repit.repit_api_server.domain.userdata.recording.entity.enums.RecordingKind;
import repit.repit_api_server.domain.userdata.recording.repository.InterviewRecordingRepository;
import repit.repit_api_server.global.exception.BusinessException;
import repit.repit_api_server.global.exception.ExternalApiException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InterviewRecordingService {

    private static final Logger log = LoggerFactory.getLogger(InterviewRecordingService.class);

    private static final String KEY_PREFIX = "interview-recordings/";
    private static final int HEADER_BYTES = 12;

    private final InterviewRepository interviewRepository;
    private final InterviewRecordingRepository recordingRepository;
    private final S3Client s3Client;
    private final FeedbackDispatchService feedbackDispatchService;

    @Value("${spring.cloud.aws.s3.bucket}")
    private String bucketName;

    /**
     * 웹이 올린 면접 파일 하나를 S3에 두고 기록을 남긴다.
     *
     * <p>웹은 답할 때마다 그 답변의 파일을, 면접을 멈출 때 면접 화면 전체를 담은 영상을 올린다.
     * 어느 쪽인지는 요청이 {@code kind}로 밝힌다. 밝히지 않으면 답변 파일로 본다.
     *
     * <p>종류를 "질문 번호가 붙었는지"로 짐작하지 않는다. 그러면 웹이 질문 번호를 빠뜨린 답변 파일이
     * 조용히 면접 전체 영상이 되어, 답변 한 토막이 그 면접의 화면으로 채점에 실린다.
     *
     * <p>형식은 확장자나 Content-Type이 아니라 파일 앞 바이트로 판단한다. 둘 다 보내는 쪽이 붙이는
     * 값이라, 믿고 받으면 열리지도 않는 파일이 그대로 분석으로 넘어간다.
     */
    public InterviewRecordingResponse upload(Long userId, Long interviewId, String rawKind, Long questionId,
                                             MultipartFile file) {
        InterviewEntity interview = interviewRepository.findById(interviewId)
                .orElseThrow(() -> BusinessException.notFound("면접을 찾을 수 없습니다"));
        if (!userId.equals(interview.getUserId())) {
            throw BusinessException.forbidden("본인의 면접에만 녹화 파일을 올릴 수 있습니다.");
        }

        RecordingKind kind = parseKind(rawKind);
        if (kind == RecordingKind.ANSWER && questionId == null) {
            throw new BusinessException("답변 파일에는 녹화한 질문 번호(questionId)가 필요합니다.", HttpStatus.BAD_REQUEST);
        }
        if (kind == RecordingKind.FULL_INTERVIEW && questionId != null) {
            throw new BusinessException("면접 화면 전체 녹화는 질문 하나에 매이지 않습니다. questionId를 빼고 보내주세요.",
                    HttpStatus.BAD_REQUEST);
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException("녹화 파일이 비어 있습니다.", HttpStatus.BAD_REQUEST);
        }

        RecordingFormat format = detectFormat(file);
        String contentType = contentTypeOf(kind, format, file.getContentType());

        String key = KEY_PREFIX + interviewId + "/" + UUID.randomUUID() + "." + format.extension();
        putObject(key, contentType, file);

        InterviewRecordingEntity recording;
        try {
            recording = recordingRepository.save(InterviewRecordingEntity.builder()
                    .interviewId(interviewId)
                    .userId(userId)
                    .kind(kind)
                    .chatQuestionId(questionId)
                    .contentType(contentType)
                    .s3Key(key)
                    .fileSize(file.getSize())
                    .build());
        } catch (RuntimeException e) {
            // 기록이 없으면 이 파일은 아무도 찾지 못한다. 버킷에 주인 없는 파일을 남기지 않는다.
            deleteQuietly(key);
            throw e;
        }

        // 파일은 이미 저장됐다. 채점 준비가 넘어져도 업로드는 성공으로 답한다 — 실패로 답하면 웹이 같은 파일을 또 올린다.
        try {
            feedbackDispatchService.onRecordingUploaded(interviewId);
        } catch (RuntimeException e) {
            log.error("녹화 파일을 받은 뒤 채점 준비를 처리하지 못했습니다. interviewId={}", interviewId, e);
        }
        return InterviewRecordingResponse.from(recording);
    }

    /** 밝히지 않으면 답변 파일이다. 웹이 훨씬 자주 올리는 쪽이고, 잘못 짚어도 질문 번호가 함께 검사된다. */
    private static RecordingKind parseKind(String rawKind) {
        if (rawKind == null || rawKind.isBlank()) {
            return RecordingKind.ANSWER;
        }
        try {
            return RecordingKind.valueOf(rawKind.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException("파일 종류(kind)는 ANSWER 또는 FULL_INTERVIEW여야 합니다.", HttpStatus.BAD_REQUEST);
        }
    }

    private RecordingFormat detectFormat(MultipartFile file) {
        byte[] header;
        try (InputStream in = file.getInputStream()) {
            header = in.readNBytes(HEADER_BYTES);
        } catch (IOException e) {
            throw new BusinessException("녹화 파일을 읽지 못했습니다.", HttpStatus.BAD_REQUEST);
        }
        RecordingFormat format = RecordingFormat.detect(header);
        if (format == null) {
            throw new BusinessException("올릴 수 없는 파일 형식입니다. MP4, WebM, Ogg, MP3, WAV만 받습니다.",
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        }
        return format;
    }

    /**
     * 저장해 둘 형식 이름.
     *
     * <p>컨테이너는 앞 바이트로 정해지지만 그 안에 그림이 있는지까지는 열어봐야 안다. 그래서 MP4나 WebM처럼
     * 둘 다 담을 수 있는 형식은 요청에 붙은 Content-Type의 앞머리만 참고한다. 틀려도 컨테이너는 맞으므로
     * 분석 서버가 파일을 열면 바로 잡힌다.
     */
    private String contentTypeOf(RecordingKind kind, RecordingFormat format, String declaredContentType) {
        if (kind == RecordingKind.FULL_INTERVIEW) {
            if (!format.canHoldVideo()) {
                throw new BusinessException("면접 화면 녹화는 영상 파일이어야 합니다.", HttpStatus.UNSUPPORTED_MEDIA_TYPE);
            }
            return format.videoContentType();
        }
        boolean declaredVideo = declaredContentType != null && declaredContentType.startsWith("video/");
        return declaredVideo && format.canHoldVideo() ? format.videoContentType() : format.audioContentType();
    }

    private void putObject(String key, String contentType, MultipartFile file) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .contentType(contentType)
                .build();
        try (InputStream in = file.getInputStream()) {
            s3Client.putObject(request, RequestBody.fromInputStream(in, file.getSize()));
        } catch (IOException e) {
            throw new BusinessException("녹화 파일을 읽지 못했습니다.", HttpStatus.BAD_REQUEST);
        } catch (SdkException e) {
            throw new ExternalApiException("녹화 파일을 저장하지 못했습니다. 잠시 후 다시 시도해주세요.", null, e);
        }
    }

    private void deleteQuietly(String key) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucketName).key(key).build());
        } catch (SdkException e) {
            log.error("기록을 남기지 못한 녹화 파일을 S3에서 지우지 못했습니다. key={}", key, e);
        }
    }
}
