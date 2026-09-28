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
import org.springframework.web.multipart.MultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import repit.repit_api_server.domain.userdata.feedback.service.FeedbackDispatchService;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.recording.dto.response.InterviewRecordingResponse;
import repit.repit_api_server.domain.userdata.recording.entity.InterviewRecordingEntity;
import repit.repit_api_server.domain.userdata.recording.entity.enums.RecordingEndReason;
import repit.repit_api_server.domain.userdata.recording.entity.enums.RecordingKind;
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
 * 웹이 올린 면접 파일 받기.
 *
 * <p>요청의 kind가 답변 음성인지 면접 화면 전체 영상인지를 정한다. 질문 번호가 붙었는지로 짐작하면,
 * 웹이 번호를 빠뜨린 답변 파일이 조용히 면접 전체 영상이 되어 답변 한 토막이 그 면접의 화면으로 채점에 실린다.
 *
 * <p>남의 면접에 파일을 붙일 수 없어야 하고, 우리가 못 여는 형식은 S3에 닿기 전에 막혀야 한다.
 * 기록을 남기지 못하면 올린 파일도 지워야 한다 — 그러지 않으면 아무도 찾지 못하는 파일이 버킷에 쌓인다.
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
    // ID3 태그로 시작하는 MP3 머리.
    private static final byte[] MP3_BYTES = {'I', 'D', '3', 3, 0, 0, 0, 0, 0, 0, 0, 0};

    @Mock
    private InterviewRepository interviewRepository;
    @Mock
    private InterviewRecordingRepository recordingRepository;
    @Mock
    private S3Client s3Client;
    @Mock
    private FeedbackDispatchService feedbackDispatchService;

    private InterviewRecordingService service;

    @BeforeEach
    void setUp() {
        service = new InterviewRecordingService(interviewRepository, recordingRepository, s3Client, feedbackDispatchService);
        ReflectionTestUtils.setField(service, "bucketName", BUCKET);
        when(interviewRepository.findById(INTERVIEW_ID)).thenReturn(Optional.of(interview(USER_ID)));
        when(recordingRepository.save(any())).thenAnswer(invocation -> {
            InterviewRecordingEntity saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "recordingId", 100L);
            return saved;
        });
    }

    @Test
    void 질문_번호가_붙은_음성은_그_질문의_답변_파일로_기록한다() {
        InterviewRecordingResponse response = service.upload(USER_ID, INTERVIEW_ID, null, QUESTION_ID, null, mp3File());

        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(put.capture(), any(RequestBody.class));
        assertThat(put.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(put.getValue().key()).startsWith("interview-recordings/42/").endsWith(".mp3");
        assertThat(put.getValue().contentType()).isEqualTo("audio/mpeg");

        ArgumentCaptor<InterviewRecordingEntity> saved = ArgumentCaptor.forClass(InterviewRecordingEntity.class);
        verify(recordingRepository).save(saved.capture());
        assertThat(saved.getValue().getS3Key()).isEqualTo(put.getValue().key());
        assertThat(saved.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getValue().getKind()).isEqualTo(RecordingKind.ANSWER);
        assertThat(saved.getValue().getChatQuestionId()).isEqualTo(QUESTION_ID);
        assertThat(saved.getValue().getContentType()).isEqualTo("audio/mpeg");
        assertThat(saved.getValue().getFileSize()).isEqualTo(MP3_BYTES.length);

        assertThat(response.recordingId()).isEqualTo(100L);
        assertThat(response.interviewId()).isEqualTo(INTERVIEW_ID);
        assertThat(response.questionId()).isEqualTo(QUESTION_ID);
    }

    /** 면접을 멈출 때 올라오는 화면 녹화다. 어느 한 질문의 것이 아니라 질문 번호가 붙지 않는다. */
    @Test
    void kind가_FULL_INTERVIEW면_면접_전체_녹화로_기록한다() {
        InterviewRecordingResponse response = service.upload(USER_ID, INTERVIEW_ID, "FULL_INTERVIEW", null, null, mp4File());

        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(put.capture(), any(RequestBody.class));
        assertThat(put.getValue().key()).endsWith(".mp4");
        assertThat(put.getValue().contentType()).isEqualTo("video/mp4");

        ArgumentCaptor<InterviewRecordingEntity> saved = ArgumentCaptor.forClass(InterviewRecordingEntity.class);
        verify(recordingRepository).save(saved.capture());
        assertThat(saved.getValue().getKind()).isEqualTo(RecordingKind.FULL_INTERVIEW);
        assertThat(saved.getValue().getChatQuestionId()).isNull();
        assertThat(saved.getValue().getContentType()).isEqualTo("video/mp4");

        assertThat(response.kind()).isEqualTo(RecordingKind.FULL_INTERVIEW);
        assertThat(response.questionId()).isNull();
    }

    /**
     * 종류를 밝히지 않은 채 질문 번호도 빠지면 어느 답변의 것인지 영영 알 수 없다. 면접 전체 영상으로
     * 받아들이면 답변 한 토막이 그 면접의 화면이 되어 채점에 실린다. 받지 않고 돌려보낸다.
     */
    @Test
    void 답변_파일에_질문_번호가_없으면_400이고_S3에_닿지_않는다() {
        assertStatus(() -> service.upload(USER_ID, INTERVIEW_ID, null, null, null, mp3File()), HttpStatus.BAD_REQUEST);
        assertStatus(() -> service.upload(USER_ID, INTERVIEW_ID, "ANSWER", null, null, mp3File()), HttpStatus.BAD_REQUEST);
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    /** 면접 전체 영상에 질문 번호가 붙으면 웹이 둘 중 하나를 잘못 보낸 것이다. 짐작해서 저장하지 않는다. */
    @Test
    void 면접_전체_녹화에_질문_번호가_붙으면_400() {
        assertStatus(() -> service.upload(USER_ID, INTERVIEW_ID, "FULL_INTERVIEW", QUESTION_ID, null, mp4File()),
                HttpStatus.BAD_REQUEST);
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    /** 음성 분석이 시간 제한으로 잘린 답변을 가려 보려면 종료 이유가 답변 파일에 붙어 있어야 한다. */
    @Test
    void 답변_녹음의_종료_이유를_함께_기록하고_소문자로_돌려준다() {
        InterviewRecordingResponse response =
                service.upload(USER_ID, INTERVIEW_ID, null, QUESTION_ID, " Timeout ", mp3File());

        ArgumentCaptor<InterviewRecordingEntity> saved = ArgumentCaptor.forClass(InterviewRecordingEntity.class);
        verify(recordingRepository).save(saved.capture());
        assertThat(saved.getValue().getEndReason()).isEqualTo(RecordingEndReason.TIMEOUT);
        assertThat(response.endReason()).isEqualTo("timeout");
    }

    @Test
    void 종료_이유를_빼면_비워_둔다() {
        service.upload(USER_ID, INTERVIEW_ID, null, QUESTION_ID, null, mp3File());

        ArgumentCaptor<InterviewRecordingEntity> saved = ArgumentCaptor.forClass(InterviewRecordingEntity.class);
        verify(recordingRepository).save(saved.capture());
        assertThat(saved.getValue().getEndReason()).isNull();
    }

    @Test
    void 모르는_종료_이유면_400이고_S3에_닿지_않는다() {
        assertStatus(() -> service.upload(USER_ID, INTERVIEW_ID, null, QUESTION_ID, "cancelled", mp3File()),
                HttpStatus.BAD_REQUEST);
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    /** 면접 전체 영상은 답변 하나를 끝낸 것이 아니다. 종료 이유가 붙으면 웹이 둘 중 하나를 잘못 보낸 것이다. */
    @Test
    void 면접_전체_녹화에_종료_이유가_붙으면_400() {
        assertStatus(() -> service.upload(USER_ID, INTERVIEW_ID, "FULL_INTERVIEW", null, "user", mp4File()),
                HttpStatus.BAD_REQUEST);
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void 모르는_kind면_400() {
        assertStatus(() -> service.upload(USER_ID, INTERVIEW_ID, "WHOLE", null, null, mp4File()), HttpStatus.BAD_REQUEST);
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void kind는_대소문자를_가리지_않는다() {
        service.upload(USER_ID, INTERVIEW_ID, " full_interview ", null, null, mp4File());

        ArgumentCaptor<InterviewRecordingEntity> saved = ArgumentCaptor.forClass(InterviewRecordingEntity.class);
        verify(recordingRepository).save(saved.capture());
        assertThat(saved.getValue().getKind()).isEqualTo(RecordingKind.FULL_INTERVIEW);
    }

    /** 브라우저에 따라 답변 음성이 WebM이나 MP4 컨테이너로 나온다. 컨테이너만 맞으면 받는다. */
    @Test
    void 답변_음성은_MP3가_아니어도_받는다() {
        MockMultipartFile webm = new MockMultipartFile("file", "answer.webm", "audio/webm",
                new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 0, 0, 0, 0, 0, 0, 0, 0});

        service.upload(USER_ID, INTERVIEW_ID, null, QUESTION_ID, null, webm);

        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(put.capture(), any(RequestBody.class));
        assertThat(put.getValue().key()).endsWith(".webm");
        assertThat(put.getValue().contentType()).isEqualTo("audio/webm");
    }

    /** 면접 화면 녹화 자리에 소리만 담는 형식이 오면 화면은 어디에도 없다. */
    @Test
    void 면접_전체_녹화_자리에_소리만_담는_형식이_오면_415() {
        assertStatus(() -> service.upload(USER_ID, INTERVIEW_ID, "FULL_INTERVIEW", null, null, mp3File()),
                HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void 없는_면접이면_404() {
        when(interviewRepository.findById(INTERVIEW_ID)).thenReturn(Optional.empty());

        assertStatus(() -> service.upload(USER_ID, INTERVIEW_ID, null, QUESTION_ID, null, mp3File()), HttpStatus.NOT_FOUND);
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void 남의_면접이면_403이고_S3에_닿지_않는다() {
        when(interviewRepository.findById(INTERVIEW_ID)).thenReturn(Optional.of(interview(OTHER_USER_ID)));

        assertStatus(() -> service.upload(USER_ID, INTERVIEW_ID, null, QUESTION_ID, null, mp3File()), HttpStatus.FORBIDDEN);
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void 빈_파일이면_400() {
        MockMultipartFile empty = new MockMultipartFile("file", "a.mp3", "audio/mpeg", new byte[0]);

        assertStatus(() -> service.upload(USER_ID, INTERVIEW_ID, null, QUESTION_ID, null, empty), HttpStatus.BAD_REQUEST);
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    /** 이름과 Content-Type은 보내는 쪽이 붙이는 값이다. 앞 바이트가 우리가 아는 형식이 아니면 막는다. */
    @Test
    void 이름과_타입이_MP4라도_내용이_아니면_415() {
        MockMultipartFile text = new MockMultipartFile("file", "a.mp4", "video/mp4",
                "not a media file".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertStatus(() -> service.upload(USER_ID, INTERVIEW_ID, null, QUESTION_ID, null, text), HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    /**
     * 컨테이너는 앞 바이트로 확정하되, 그 안이 소리인지 그림인지는 요청이 말한 대로 적어 둔다.
     * 답변 파일 자리에 MP4가 와도 웹이 video라고 했으면 그대로 남긴다.
     */
    @Test
    void 답변_파일은_영상이라고_밝혀도_음성으로_기록한다() {
        MockMultipartFile video = new MockMultipartFile("file", "answer.mp4", "video/mp4", MP4_BYTES);

        service.upload(USER_ID, INTERVIEW_ID, null, QUESTION_ID, null, video);

        ArgumentCaptor<InterviewRecordingEntity> saved = ArgumentCaptor.forClass(InterviewRecordingEntity.class);
        verify(recordingRepository).save(saved.capture());
        assertThat(saved.getValue().getKind()).isEqualTo(RecordingKind.ANSWER);
        // 음성 분석은 video/ MIME을 받지 않는다. 그대로 저장하면 그 한 건 때문에 요청 전체가 거절된다.
        assertThat(saved.getValue().getContentType()).isEqualTo("audio/mp4");
    }

    /** 분석 서버가 받는 상한이다. 받아 두면 그 답변은 영영 분석되지 않으니 업로드에서 돌려보낸다. */
    @Test
    void 답변_음성이_100MB를_넘으면_413이고_S3에_닿지_않는다() {
        MultipartFile tooLarge = new MockMultipartFile("file", "answer.mp3", "audio/mpeg", MP3_BYTES) {
            @Override
            public long getSize() {
                return 100_000_001L;
            }
        };

        assertStatus(() -> service.upload(USER_ID, INTERVIEW_ID, null, QUESTION_ID, null, tooLarge),
                HttpStatus.CONTENT_TOO_LARGE);
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void S3_저장이_실패하면_외부_오류로_알리고_기록하지_않는다() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(SdkClientException.create("down"));

        assertThatThrownBy(() -> service.upload(USER_ID, INTERVIEW_ID, null, QUESTION_ID, null, mp3File()))
                .isInstanceOf(ExternalApiException.class);
        verify(recordingRepository, never()).save(any());
    }

    @Test
    void 기록이_실패하면_올린_파일을_지운다() {
        doThrow(new IllegalStateException("db down")).when(recordingRepository).save(any());

        assertThatThrownBy(() -> service.upload(USER_ID, INTERVIEW_ID, null, QUESTION_ID, null, mp3File()))
                .isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(put.capture(), any(RequestBody.class));
        ArgumentCaptor<DeleteObjectRequest> delete = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(delete.capture());
        assertThat(delete.getValue().key()).isEqualTo(put.getValue().key());
    }

    @Test
    void 저장한_뒤에_채점_대기에_알린다() {
        service.upload(USER_ID, INTERVIEW_ID, null, QUESTION_ID, null, mp3File());

        verify(feedbackDispatchService).onRecordingUploaded(INTERVIEW_ID);
    }

    @Test
    void 채점_대기_처리가_실패해도_업로드는_성공으로_답한다() {
        doThrow(new IllegalStateException("ai down")).when(feedbackDispatchService).onRecordingUploaded(INTERVIEW_ID);

        InterviewRecordingResponse response = service.upload(USER_ID, INTERVIEW_ID, null, QUESTION_ID, null, mp3File());

        // 실패로 답하면 웹은 이미 저장된 파일을 다시 올린다.
        assertThat(response.recordingId()).isEqualTo(100L);
    }

    @Test
    void 기록이_실패하면_채점_대기에_알리지_않는다() {
        doThrow(new IllegalStateException("db down")).when(recordingRepository).save(any());

        assertThatThrownBy(() -> service.upload(USER_ID, INTERVIEW_ID, null, QUESTION_ID, null, mp3File()))
                .isInstanceOf(IllegalStateException.class);
        verify(feedbackDispatchService, never()).onRecordingUploaded(any());
    }

    private static MockMultipartFile mp4File() {
        return new MockMultipartFile("file", "interview-1.mp4", "video/mp4", MP4_BYTES);
    }

    private static MockMultipartFile mp3File() {
        return new MockMultipartFile("file", "answer-1.mp3", "audio/mpeg", MP3_BYTES);
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
