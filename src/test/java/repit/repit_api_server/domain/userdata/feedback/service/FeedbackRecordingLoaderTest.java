package repit.repit_api_server.domain.userdata.feedback.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import repit.repit_api_server.domain.userdata.answer.entity.AnswerEntity;
import repit.repit_api_server.domain.userdata.feedback.dto.request.FeedbackRecording;
import repit.repit_api_server.domain.userdata.question.entity.QuestionEntity;
import repit.repit_api_server.domain.userdata.question.entity.enums.Type;
import repit.repit_api_server.domain.userdata.recording.entity.InterviewRecordingEntity;
import repit.repit_api_server.domain.userdata.recording.repository.InterviewRecordingRepository;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 채점 요청에 실을 답변 영상과 질문·답변의 매핑.
 *
 * <p>분석 서버가 영상과 질문을 같은 요청 안에서 이어 읽을 수 있어야 한다. 웹이 붙여 보낸 채팅 서버
 * 번호를 그대로 실으면 질문 목록의 어느 것과도 맞지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class FeedbackRecordingLoaderTest {

    private static final Long INTERVIEW_ID = 42L;

    @Mock
    private InterviewRecordingRepository recordingRepository;

    // 서명은 네트워크 없이 로컬에서 계산된다. 실제 서명기로 주소 모양까지 본다.
    private S3Presigner s3Presigner;
    private FeedbackRecordingLoader loader;

    @BeforeEach
    void setUp() {
        s3Presigner = S3Presigner.builder()
                .region(Region.AP_NORTHEAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIATEST", "secret")))
                .build();
        loader = new FeedbackRecordingLoader(recordingRepository, s3Presigner);
        ReflectionTestUtils.setField(loader, "bucketName", "repit-bucket");
        ReflectionTestUtils.setField(loader, "recordingUrlTtl", Duration.ofHours(6));
    }

    @AfterEach
    void tearDown() {
        s3Presigner.close();
    }

    @Test
    void 채팅_서버_질문_번호로_질문과_답변에_잇고_서명_주소를_싣는다() {
        when(recordingRepository.findAllByInterviewIdOrderByRecordingIdAsc(INTERVIEW_ID)).thenReturn(List.of(
                recording(301L, 1L), recording(302L, -5L)));

        List<FeedbackRecording> loaded = loader.load(INTERVIEW_ID,
                List.of(question(101L, 1L), question(102L, -5L)), List.of(answer(201L, 101L), answer(202L, 102L)));

        assertThat(loaded).extracting(FeedbackRecording::getRecordingId).containsExactly("301", "302");
        assertThat(loaded).extracting(FeedbackRecording::getQuestionId).containsExactly("101", "102");
        assertThat(loaded).extracting(FeedbackRecording::getAnswerId).containsExactly("201", "202");

        FeedbackRecording first = loaded.getFirst();
        assertThat(first.getContentType()).isEqualTo("video/mp4");
        assertThat(first.getFileSize()).isEqualTo(1024L);
        assertThat(first.getUploadedAt()).isEqualTo(OffsetDateTime.parse("2026-09-17T07:56:31Z"));
        assertThat(first.getVideoUrl())
                .contains("repit-bucket")
                .contains("interview-recordings/42/301.mp4")
                .contains("X-Amz-Expires=21600");
    }

    /** 같은 질문을 다시 녹화했으면 나중 것이 그 답변이다. 둘 다 실으면 분석 서버는 어느 쪽을 볼지 모른다. */
    @Test
    void 같은_질문에_여러_번_올라오면_가장_나중_영상_하나만_싣는다() {
        when(recordingRepository.findAllByInterviewIdOrderByRecordingIdAsc(INTERVIEW_ID)).thenReturn(List.of(
                recording(301L, 1L), recording(305L, 1L)));

        List<FeedbackRecording> loaded = loader.load(INTERVIEW_ID, List.of(question(101L, 1L)), List.of(answer(201L, 101L)));

        assertThat(loaded).extracting(FeedbackRecording::getRecordingId).containsExactly("305");
    }

    @Test
    void 질문_진행_순서대로_싣는다() {
        // 꼬리질문 영상이 먼저 올라와도 요청의 questions 순서를 따른다.
        when(recordingRepository.findAllByInterviewIdOrderByRecordingIdAsc(INTERVIEW_ID)).thenReturn(List.of(
                recording(301L, -5L), recording(302L, 1L)));

        List<FeedbackRecording> loaded = loader.load(INTERVIEW_ID,
                List.of(question(101L, 1L), question(102L, -5L)), List.of());

        assertThat(loaded).extracting(FeedbackRecording::getQuestionId).containsExactly("101", "102");
    }

    @Test
    void 질문에_이을_수_없는_영상은_싣지_않는다() {
        when(recordingRepository.findAllByInterviewIdOrderByRecordingIdAsc(INTERVIEW_ID)).thenReturn(List.of(
                recording(301L, null), recording(302L, 99L), recording(303L, 1L)));

        List<FeedbackRecording> loaded = loader.load(INTERVIEW_ID, List.of(question(101L, 1L)), List.of());

        assertThat(loaded).extracting(FeedbackRecording::getRecordingId).containsExactly("303");
    }

    @Test
    void 답변이_저장되지_않은_질문의_영상은_답변_id를_비운다() {
        when(recordingRepository.findAllByInterviewIdOrderByRecordingIdAsc(INTERVIEW_ID)).thenReturn(List.of(recording(301L, 1L)));

        List<FeedbackRecording> loaded = loader.load(INTERVIEW_ID, List.of(question(101L, 1L)), List.of());

        assertThat(loaded.getFirst().getQuestionId()).isEqualTo("101");
        assertThat(loaded.getFirst().getAnswerId()).isNull();
    }

    @Test
    void 영상이_없으면_빈_목록이다() {
        when(recordingRepository.findAllByInterviewIdOrderByRecordingIdAsc(INTERVIEW_ID)).thenReturn(List.of());

        assertThat(loader.load(INTERVIEW_ID, List.of(question(101L, 1L)), List.of())).isEmpty();
    }

    private static QuestionEntity question(Long id, Long chatId) {
        return QuestionEntity.builder()
                .questionId(id).interviewId(INTERVIEW_ID).chatQuestionId(chatId).type(Type.ORIGINAL)
                .content("질문").build();
    }

    private static AnswerEntity answer(Long id, Long questionId) {
        return AnswerEntity.builder()
                .answerId(id).interviewId(INTERVIEW_ID).questionId(questionId).userId(7L).content("답변").build();
    }

    private static InterviewRecordingEntity recording(Long id, Long chatQuestionId) {
        return InterviewRecordingEntity.builder()
                .recordingId(id).interviewId(INTERVIEW_ID).userId(7L).chatQuestionId(chatQuestionId)
                .s3Key("interview-recordings/42/" + id + ".mp4").fileSize(1024L)
                .createdAt(LocalDateTime.parse("2026-09-17T07:56:31"))
                .build();
    }
}
