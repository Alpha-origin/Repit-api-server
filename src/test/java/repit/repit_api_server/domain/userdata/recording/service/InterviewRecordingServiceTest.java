package repit.repit_api_server.domain.userdata.recording.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.recording.dto.response.InterviewRecordingResponse;
import repit.repit_api_server.domain.userdata.recording.entity.InterviewRecordingEntity;
import repit.repit_api_server.domain.userdata.recording.repository.InterviewRecordingRepository;
import repit.repit_api_server.global.exception.BusinessException;
import repit.repit_api_server.global.exception.ExternalApiException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 웹이 올린 면접 녹화 파일 받기.
 *
 * <p>남의 면접에 영상을 붙일 수 없어야 하고, MP4가 아닌 것은 S3에 닿기 전에 막혀야 한다.
 * 기록을 남기지 못하면 올린 영상도 지워야 한다 — 그러지 않으면 아무도 찾지 못하는 영상이 버킷에 쌓인다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InterviewRecordingServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long OTHER_USER_ID = 8L;
    private static final Long INTERVIEW_ID = 42L;
    private static final Long QUESTION_ID = 3L;
    private static final String BUCKET = "repit-bucket";

    // ftyp 박스로 시작하는 최소 MP4 머리. 크기 4바이트 + "ftyp" + 브랜드.
    private static final byte[] MP4_BYTES = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};

    @Mock
    private InterviewRepository interviewRepository;
    @Mock
    private InterviewRecordingRepository recordingRepository;
    @Mock
    private S3Client s3Client;

    private InterviewRecordingService service;

    @BeforeEach
    void setUp() {
        service = new InterviewRecordingService(interviewRepository, recordingRepository, s3Client);
        ReflectionTestUtils.setField(service, "bucketName", BUCKET);
        when(interviewRepository.findById(INTERVIEW_ID)).thenReturn(Optional.of(interview(USER_ID)));
        when(recordingRepository.save(any())).thenAnswer(invocation -> {
            InterviewRecordingEntity saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "recordingId", 100L);
            return saved;
        });
    }

    @Test
    void MP4를_S3에_올리고_면접과_질문에_묶어_기록한다() {
        InterviewRecordingResponse response = service.upload(USER_ID, INTERVIEW_ID, QUESTION_ID, mp4File());

        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(put.capture(), any(RequestBody.class));
        assertThat(put.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(put.getValue().key()).startsWith("interview-recordings/42/").endsWith(".mp4");
        assertThat(put.getValue().contentType()).isEqualTo("video/mp4");

        ArgumentCaptor<InterviewRecordingEntity> saved = ArgumentCaptor.forClass(InterviewRecordingEntity.class);
        verify(recordingRepository).save(saved.capture());
        assertThat(saved.getValue().getS3Key()).isEqualTo(put.getValue().key());
        assertThat(saved.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getValue().getChatQuestionId()).isEqualTo(QUESTION_ID);
        assertThat(saved.getValue().getFileSize()).isEqualTo(MP4_BYTES.length);

        assertThat(response.recordingId()).isEqualTo(100L);
        assertThat(response.interviewId()).isEqualTo(INTERVIEW_ID);
        assertThat(response.questionId()).isEqualTo(QUESTION_ID);
    }

    @Test
    void 질문_번호가_없어도_받는다() {
        InterviewRecordingResponse response = service.upload(USER_ID, INTERVIEW_ID, null, mp4File());

        assertThat(response.questionId()).isNull();
        verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void 없는_면접이면_404() {
        when(interviewRepository.findById(INTERVIEW_ID)).thenReturn(Optional.empty());

        assertStatus(() -> service.upload(USER_ID, INTERVIEW_ID, QUESTION_ID, mp4File()), HttpStatus.NOT_FOUND);
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void 남의_면접이면_403이고_S3에_닿지_않는다() {
        when(interviewRepository.findById(INTERVIEW_ID)).thenReturn(Optional.of(interview(OTHER_USER_ID)));

        assertStatus(() -> service.upload(USER_ID, INTERVIEW_ID, QUESTION_ID, mp4File()), HttpStatus.FORBIDDEN);
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void 빈_파일이면_400() {
        MockMultipartFile empty = new MockMultipartFile("file", "a.mp4", "video/mp4", new byte[0]);

        assertStatus(() -> service.upload(USER_ID, INTERVIEW_ID, QUESTION_ID, empty), HttpStatus.BAD_REQUEST);
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void 이름과_타입이_MP4라도_내용이_아니면_415() {
        MockMultipartFile webm = new MockMultipartFile("file", "a.mp4", "video/mp4",
                new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 0, 0, 0, 0, 0});

        assertStatus(() -> service.upload(USER_ID, INTERVIEW_ID, QUESTION_ID, webm), HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void S3_저장이_실패하면_외부_오류로_알리고_기록하지_않는다() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(SdkClientException.create("down"));

        assertThatThrownBy(() -> service.upload(USER_ID, INTERVIEW_ID, QUESTION_ID, mp4File()))
                .isInstanceOf(ExternalApiException.class);
        verify(recordingRepository, never()).save(any());
    }

    @Test
    void 기록이_실패하면_올린_영상을_지운다() {
        doThrow(new IllegalStateException("db down")).when(recordingRepository).save(any());

        assertThatThrownBy(() -> service.upload(USER_ID, INTERVIEW_ID, QUESTION_ID, mp4File()))
                .isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(put.capture(), any(RequestBody.class));
        ArgumentCaptor<DeleteObjectRequest> delete = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(delete.capture());
        assertThat(delete.getValue().key()).isEqualTo(put.getValue().key());
    }

    private static MockMultipartFile mp4File() {
        return new MockMultipartFile("file", "interview-1.mp4", "video/mp4", MP4_BYTES);
    }

    private static InterviewEntity interview(Long ownerId) {
        return InterviewEntity.builder()
                .interviewId(INTERVIEW_ID)
                .userId(ownerId)
                .sessionId("session-1")
                .status(Status.IN_PROGRESS)
                .build();
    }

    private static void assertStatus(Runnable call, HttpStatus status) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getStatus()).isEqualTo(status));
    }
}
