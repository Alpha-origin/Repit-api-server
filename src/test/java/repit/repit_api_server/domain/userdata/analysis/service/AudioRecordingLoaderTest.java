package repit.repit_api_server.domain.userdata.analysis.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import repit.repit_api_server.domain.userdata.analysis.service.AudioRecordingLoader.AnswerRecording;
import repit.repit_api_server.domain.userdata.answer.entity.AnswerEntity;
import repit.repit_api_server.domain.userdata.question.entity.QuestionEntity;
import repit.repit_api_server.domain.userdata.question.entity.enums.Type;
import repit.repit_api_server.domain.userdata.recording.entity.InterviewRecordingEntity;
import repit.repit_api_server.domain.userdata.recording.entity.enums.RecordingKind;
import repit.repit_api_server.domain.userdata.recording.repository.InterviewRecordingRepository;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 음성 분석에 실을 답변 음성.
 *
 * <p>웹이 붙여 보낸 채팅 서버 번호를 우리 질문·답변 PK로 옮겨야 분석 결과를 질문과 이을 수 있다.
 */
@ExtendWith(MockitoExtension.class)
class AudioRecordingLoaderTest {

    private static final Long INTERVIEW_ID = 42L;

    @Mock
    private InterviewRecordingRepository recordingRepository;

    // 서명은 네트워크 없이 로컬에서 계산된다. 실제 서명기로 주소 모양까지 본다.
    private S3Presigner s3Presigner;
    private AudioRecordingLoader loader;

    @BeforeEach
    void setUp() {
        s3Presigner = S3Presigner.builder()
                .region(Region.AP_NORTHEAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIATEST", "secret")))
                .build();
        loader = new AudioRecordingLoader(recordingRepository, s3Presigner);
        ReflectionTestUtils.setField(loader, "bucketName", "repit-bucket");
        ReflectionTestUtils.setField(loader, "recordingUrlTtl", Duration.ofHours(6));
    }

    @AfterEach
    void tearDown() {
        s3Presigner.close();
    }

    @Test
    void 채팅_서버_질문_번호로_질문과_답변에_잇는다() {
        answerRecordings(recording(301L, 1L), recording(302L, -5L));

        List<AnswerRecording> loaded = loader.load(INTERVIEW_ID,
                List.of(question(101L, 1L), question(102L, -5L)), List.of(answer(201L, 101L), answer(202L, 102L)));

        assertThat(loaded).extracting(l -> l.recording().getRecordingId()).containsExactly(301L, 302L);
        assertThat(loaded).extracting(AnswerRecording::questionId).containsExactly(101L, 102L);
        assertThat(loaded).extracting(AnswerRecording::answerId).containsExactly(201L, 202L);
    }

    /** 같은 질문을 다시 녹음했으면 나중 것이 그 답변이다. 둘 다 실으면 분석 서버는 어느 쪽을 볼지 모른다. */
    @Test
    void 같은_질문에_여러_번_올라오면_가장_나중_것_하나만_싣는다() {
        answerRecordings(recording(301L, 1L), recording(305L, 1L));

        List<AnswerRecording> loaded = loader.load(INTERVIEW_ID, List.of(question(101L, 1L)), List.of(answer(201L, 101L)));

        assertThat(loaded).extracting(l -> l.recording().getRecordingId()).containsExactly(305L);
    }

    @Test
    void 질문_진행_순서대로_싣는다() {
        // 꼬리질문 음성이 먼저 올라와도 질문 순서를 따른다.
        answerRecordings(recording(301L, -5L), recording(302L, 1L));

        List<AnswerRecording> loaded = loader.load(INTERVIEW_ID,
                List.of(question(101L, 1L), question(102L, -5L)), List.of(answer(201L, 101L), answer(202L, 102L)));

        assertThat(loaded).extracting(AnswerRecording::questionId).containsExactly(101L, 102L);
    }

    @Test
    void 질문에_이을_수_없는_음성은_싣지_않는다() {
        answerRecordings(recording(301L, null), recording(302L, 99L), recording(303L, 1L));

        List<AnswerRecording> loaded = loader.load(INTERVIEW_ID, List.of(question(101L, 1L)), List.of(answer(201L, 101L)));

        assertThat(loaded).extracting(l -> l.recording().getRecordingId()).containsExactly(303L);
    }

    /** 분석 서버는 answerId를 반드시 받는다. 비워 보내면 요청 전체가 거부된다. */
    @Test
    void 답변이_저장되지_않은_질문의_음성은_싣지_않는다() {
        answerRecordings(recording(301L, 1L));

        assertThat(loader.load(INTERVIEW_ID, List.of(question(101L, 1L)), List.of())).isEmpty();
    }

    @Test
    void 음성이_없으면_빈_목록이다() {
        answerRecordings();

        assertThat(loader.load(INTERVIEW_ID, List.of(question(101L, 1L)), List.of(answer(201L, 101L)))).isEmpty();
    }

    @Test
    void 서명_주소는_버킷의_그_파일을_유효_시간만큼_연다() {
        assertThat(loader.presign("interview-recordings/42/301.mp3"))
                .contains("repit-bucket")
                .contains("interview-recordings/42/301.mp3")
                .contains("X-Amz-Expires=21600");
    }

    private void answerRecordings(InterviewRecordingEntity... recordings) {
        when(recordingRepository.findAllByInterviewIdAndKindOrderByRecordingIdAsc(INTERVIEW_ID, RecordingKind.ANSWER))
                .thenReturn(List.of(recordings));
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
                .recordingId(id).interviewId(INTERVIEW_ID).userId(7L)
                .kind(RecordingKind.ANSWER).chatQuestionId(chatQuestionId).contentType("audio/mpeg")
                .s3Key("interview-recordings/42/" + id + ".mp3").fileSize(1024L)
                .createdAt(LocalDateTime.parse("2026-09-17T07:56:31"))
                .build();
    }
}
