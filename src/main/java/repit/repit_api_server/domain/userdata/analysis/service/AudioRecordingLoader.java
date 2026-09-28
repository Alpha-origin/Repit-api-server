package repit.repit_api_server.domain.userdata.analysis.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import repit.repit_api_server.domain.userdata.answer.entity.AnswerEntity;
import repit.repit_api_server.domain.userdata.question.entity.QuestionEntity;
import repit.repit_api_server.domain.userdata.recording.entity.InterviewRecordingEntity;
import repit.repit_api_server.domain.userdata.recording.entity.enums.RecordingKind;
import repit.repit_api_server.domain.userdata.recording.repository.InterviewRecordingRepository;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 음성 분석에 실을 답변 음성을 모은다.
 *
 * <p>웹은 질문에 답할 때마다 그 답변의 음성을 채팅 서버 질문 번호와 함께 올린다. 채팅 서버는 면접을 마칠 때
 * 같은 번호를 질문 id로 넘긴다(둘 다 채팅 세션의 ChatQuestion.questionId). 우리 질문 PK는 파일이 올라올 때
 * 아직 없고 기록이 다시 오면 바뀌므로, 요청을 만드는 이 시점에 번호를 PK로 옮긴다.
 *
 * <ul>
 *   <li>질문 하나에 파일 하나. 같은 질문에 여러 번 올라왔으면 가장 나중 것이 그 답변의 파일이다.</li>
 *   <li>기록에 없는 질문 번호의 파일은 어느 답변에도 붙일 수 없어 싣지 않는다.</li>
 *   <li>저장된 답변이 없는 질문의 파일도 싣지 않는다. 분석 서버는 답변 id를 반드시 받는다.</li>
 *   <li>질문 진행 순서대로 싣는다.</li>
 * </ul>
 *
 * <p>파일은 공개 버킷에 두지 않으므로 분석 서버가 잠깐 내려받을 수 있는 서명 주소로 넘긴다.
 */
@Component
@RequiredArgsConstructor
public class AudioRecordingLoader {

    private static final Logger log = LoggerFactory.getLogger(AudioRecordingLoader.class);

    private final InterviewRecordingRepository recordingRepository;
    private final S3Presigner s3Presigner;

    @Value("${spring.cloud.aws.s3.bucket}")
    private String bucketName;

    // 분석 서버가 파일을 내려받을 수 있는 시간. 접수 뒤 작업이 밀려도 만료되지 않을 만큼 둔다.
    @Value("${app.audio-analysis.recording-url-ttl:6h}")
    private Duration recordingUrlTtl;

    /** 질문·답변에 이어 붙인 답변 음성 하나. */
    public record AnswerRecording(InterviewRecordingEntity recording, Long questionId, Long answerId) {
    }

    /**
     * 질문 진행 순서대로 정리한 답변 음성.
     *
     * @param questions 면접 기록의 질문, 진행 순서대로
     * @param answers   면접 기록의 답변
     */
    public List<AnswerRecording> load(Long interviewId, List<QuestionEntity> questions, List<AnswerEntity> answers) {
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
            log.warn("질문에 이을 수 없는 답변 파일을 음성 분석에서 뺍니다. interviewId={}, recordingIds={}",
                    interviewId, unmatched.stream().map(InterviewRecordingEntity::getRecordingId).toList());
        }

        Map<Long, Long> answerIdByQuestionId = answers.stream()
                .collect(Collectors.toMap(AnswerEntity::getQuestionId, AnswerEntity::getAnswerId, (first, later) -> later));

        List<AnswerRecording> loaded = new ArrayList<>();
        for (QuestionEntity question : questions) {
            InterviewRecordingEntity recording = latestByChatId.get(question.getChatQuestionId());
            if (recording == null) {
                continue;
            }
            Long answerId = answerIdByQuestionId.get(question.getQuestionId());
            if (answerId == null) {
                log.warn("저장된 답변이 없는 질문의 음성이라 음성 분석에서 뺍니다. interviewId={}, questionId={}, recordingId={}",
                        interviewId, question.getQuestionId(), recording.getRecordingId());
                continue;
            }
            loaded.add(new AnswerRecording(recording, question.getQuestionId(), answerId));
        }
        return loaded;
    }

    /** 분석 서버가 잠깐 내려받을 수 있는 서명 주소. */
    public String presign(String key) {
        GetObjectPresignRequest request = GetObjectPresignRequest.builder()
                .signatureDuration(recordingUrlTtl)
                .getObjectRequest(get -> get.bucket(bucketName).key(key))
                .build();
        return s3Presigner.presignGetObject(request).url().toString();
    }
}
