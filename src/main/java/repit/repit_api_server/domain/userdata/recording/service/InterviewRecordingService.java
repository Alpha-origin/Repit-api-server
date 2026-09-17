package repit.repit_api_server.domain.userdata.recording.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.recording.dto.response.InterviewRecordingResponse;
import repit.repit_api_server.domain.userdata.recording.entity.InterviewRecordingEntity;
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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InterviewRecordingService {

    private static final Logger log = LoggerFactory.getLogger(InterviewRecordingService.class);

    private static final String MP4_CONTENT_TYPE = "video/mp4";
    private static final String KEY_PREFIX = "interview-recordings/";
    // MP4는 첫 박스가 ftyp다. 앞 4바이트는 박스 크기라 그 뒤 4바이트를 본다.
    private static final byte[] MP4_BOX_TYPE = "ftyp".getBytes(StandardCharsets.US_ASCII);

    private final InterviewRepository interviewRepository;
    private final InterviewRecordingRepository recordingRepository;
    private final S3Client s3Client;
    private final RecordingAnalysisService recordingAnalysisService;

    @Value("${spring.cloud.aws.s3.bucket}")
    private String bucketName;

    /**
     * 웹이 녹화한 MP4 하나를 받아 S3에 올리고 기록을 남긴다.
     *
     * <p>파일 형식은 확장자나 Content-Type이 아니라 앞 바이트로 판단한다. 둘 다 보내는 쪽이
     * 마음대로 붙이는 값이라, 믿고 받으면 MP4가 아닌 파일이 뒤에서 분석으로 넘어간다.
     */
    public InterviewRecordingResponse upload(Long userId, Long interviewId, Long questionId, MultipartFile file) {
        InterviewEntity interview = interviewRepository.findById(interviewId)
                .orElseThrow(() -> BusinessException.notFound("면접을 찾을 수 없습니다"));
        if (!userId.equals(interview.getUserId())) {
            throw BusinessException.forbidden("본인의 면접에만 녹화 파일을 올릴 수 있습니다.");
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException("녹화 파일이 비어 있습니다.", HttpStatus.BAD_REQUEST);
        }
        if (!isMp4(file)) {
            throw new BusinessException("MP4 파일만 올릴 수 있습니다.", HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        }

        String key = KEY_PREFIX + interviewId + "/" + UUID.randomUUID() + ".mp4";
        putObject(key, file);

        InterviewRecordingEntity recording;
        try {
            recording = recordingRepository.save(InterviewRecordingEntity.builder()
                    .interviewId(interviewId)
                    .userId(userId)
                    .chatQuestionId(questionId)
                    .s3Key(key)
                    .fileSize(file.getSize())
                    .build());
        } catch (RuntimeException e) {
            // 기록이 없으면 이 영상은 아무도 찾지 못한다. 버킷에 주인 없는 영상을 남기지 않는다.
            deleteQuietly(key);
            throw e;
        }

        // 영상은 이미 저장됐다. 분석 전달이 넘어져도 업로드는 성공으로 답한다 — 실패로 답하면 웹이 같은 영상을 또 올린다.
        try {
            recordingAnalysisService.onRecordingUploaded(interviewId);
        } catch (RuntimeException e) {
            log.error("녹화 파일을 받은 뒤 분석 전달을 처리하지 못했습니다. interviewId={}", interviewId, e);
        }
        return InterviewRecordingResponse.from(recording);
    }

    private boolean isMp4(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            byte[] header = in.readNBytes(8);
            return header.length == 8 && Arrays.equals(header, 4, 8, MP4_BOX_TYPE, 0, 4);
        } catch (IOException e) {
            throw new BusinessException("녹화 파일을 읽지 못했습니다.", HttpStatus.BAD_REQUEST);
        }
    }

    private void putObject(String key, MultipartFile file) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .contentType(MP4_CONTENT_TYPE)
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
