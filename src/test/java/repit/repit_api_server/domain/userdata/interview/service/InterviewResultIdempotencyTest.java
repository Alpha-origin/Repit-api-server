package repit.repit_api_server.domain.userdata.interview.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import repit.repit_api_server.domain.userdata.answer.entity.AnswerEntity;
import repit.repit_api_server.domain.userdata.answer.repository.AnswerRepository;
import repit.repit_api_server.domain.userdata.feedback.service.FeedbackDispatchService;
import repit.repit_api_server.domain.userdata.interview.dto.request.SaveInterviewRequest;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.question.entity.QuestionEntity;
import repit.repit_api_server.domain.userdata.question.repository.QuestionRepository;
import repit.repit_api_server.global.exception.BusinessException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 채팅 서버가 같은 면접 기록을 여러 번 보내는 경우(N-3). 실제 DB에 대고 본다.
 *
 * <p>동시에 온 요청은 각자 트랜잭션에서 돈다. 테스트 트랜잭션으로 감싸지 않고, 끝나면 만든 면접과 기록을 지운다.
 */
@SpringBootTest
class InterviewResultIdempotencyTest {

    @Autowired
    private ChatInterviewResultService chatInterviewResultService;
    @Autowired
    private InterviewRepository interviewRepository;
    @Autowired
    private QuestionRepository questionRepository;
    @Autowired
    private AnswerRepository answerRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;

    // 채점·음성 분석 준비는 분석 서버를 부른다. 여기서 볼 것은 몇 번 불렸는지다.
    @MockitoBean
    private FeedbackDispatchService feedbackDispatchService;

    private Long interviewId;

    @BeforeEach
    void setUp() {
        interviewId = interviewRepository.save(InterviewEntity.builder()
                .userId(9L)
                .mode(InterviewMode.SOLO)
                .sessionId(UUID.randomUUID().toString())
                .status(Status.IN_PROGRESS)
                .build()).getInterviewId();
    }

    @AfterEach
    void cleanUp() {
        transactionTemplate.executeWithoutResult(status -> {
            answerRepository.deleteAllByInterviewId(interviewId);
            questionRepository.deleteAllByInterviewId(interviewId);
            interviewRepository.deleteById(interviewId);
        });
    }

    private SaveInterviewRequest record(String answerContent) {
        return record(Status.COMPLETED, answerContent);
    }

    private SaveInterviewRequest record(Status status, String answerContent) {
        return new SaveInterviewRequest(null, interviewId, 9L, status, null, List.of(
                new SaveInterviewRequest.QnA(
                        new SaveInterviewRequest.Question(1L, null, "tech_choice", "Redis를 왜 썼나요?",
                                "선택 근거", "캐시 선택 근거", List.of()),
                        new SaveInterviewRequest.Answer(1L, 60, answerContent, LocalDateTime.now())),
                new SaveInterviewRequest.QnA(
                        new SaveInterviewRequest.Question(-1L, null, "follow", "다른 선택지는요?",
                                "대안 비교", null, List.of()),
                        new SaveInterviewRequest.Answer(-1L, 40, "로컬 캐시도 봤습니다.", LocalDateTime.now()))));
    }

    private List<QuestionEntity> questions() {
        return questionRepository.findAllByInterviewIdOrderByQuestionIdAsc(interviewId);
    }

    @Test
    void 같은_기록을_다시_받아도_질문과_답변이_그대로다() {
        chatInterviewResultService.handleResult(record("조회가 쓰기보다 많았습니다."));
        List<QuestionEntity> first = questions();
        List<AnswerEntity> firstAnswers = answerRepository.findAllByInterviewIdOrderByAnswerIdAsc(interviewId);

        // 응답을 받지 못한 채팅 서버가 다시 보낸다. 내용이 달라도 처음 것을 둔다.
        chatInterviewResultService.handleResult(record("다시 보낸 다른 답변"));
        chatInterviewResultService.handleResult(record("조회가 쓰기보다 많았습니다."));

        assertThat(questions()).extracting(QuestionEntity::getQuestionId)
                .containsExactlyElementsOf(first.stream().map(QuestionEntity::getQuestionId).toList());
        assertThat(questions()).extracting(QuestionEntity::getCreatedAt)
                .containsExactlyElementsOf(first.stream().map(QuestionEntity::getCreatedAt).toList());
        assertThat(answerRepository.findAllByInterviewIdOrderByAnswerIdAsc(interviewId))
                .extracting(AnswerEntity::getAnswerId, AnswerEntity::getContent)
                .containsExactlyElementsOf(firstAnswers.stream()
                        .map(answer -> tuple(answer.getAnswerId(), answer.getContent()))
                        .toList());
        assertThat(interviewRepository.findById(interviewId).orElseThrow().getStatus()).isEqualTo(Status.COMPLETED);
        // 다시 받은 기록에도 채점 준비를 부른다. 빠진 대기 행을 채우고, 있는 것은 다시 요청하지 않는다
        // (FeedbackDispatchServiceTest가 확인한다).
        verify(feedbackDispatchService, times(3)).onTranscriptSaved(interviewId);
    }

    /** 상태를 COMPLETED로 고쳐 받지 않는다. 거절은 면접을 진행 중으로 둬, 같은 기록이 올바른 상태로 오면 처음부터 저장된다. */
    @Test
    void 새_기록의_상태가_없거나_진행_중이면_422이고_면접은_그대로다() {
        for (Status invalid : java.util.Arrays.asList(null, Status.IN_PROGRESS)) {
            assertThatThrownBy(() -> chatInterviewResultService.handleResult(record(invalid, "답변")))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT));
        }

        assertThat(interviewRepository.findById(interviewId).orElseThrow().getStatus()).isEqualTo(Status.IN_PROGRESS);
        assertThat(questions()).isEmpty();
        verify(feedbackDispatchService, never()).onTranscriptSaved(interviewId);

        chatInterviewResultService.handleResult(record(Status.COMPLETED, "답변"));
        assertThat(interviewRepository.findById(interviewId).orElseThrow().getStatus()).isEqualTo(Status.COMPLETED);
        assertThat(questions()).hasSize(2);
    }

    /** 중단 기록도 질문·답변을 남긴다. 채점 준비만 하지 않고, 뒤늦은 완료 요청이 와도 중단을 지킨다. */
    @Test
    void 중단_기록은_보존하고_채점을_준비하지_않으며_뒤늦은_완료가_바꾸지_못한다() {
        chatInterviewResultService.handleResult(record(Status.ABANDONED, "여기까지 답했습니다."));
        List<Long> first = questions().stream().map(QuestionEntity::getQuestionId).toList();

        chatInterviewResultService.handleResult(record(Status.COMPLETED, "다른 답변"));
        chatInterviewResultService.handleResult(record(null, "상태 없는 재전송"));

        assertThat(interviewRepository.findById(interviewId).orElseThrow().getStatus()).isEqualTo(Status.ABANDONED);
        assertThat(questions()).extracting(QuestionEntity::getQuestionId).containsExactlyElementsOf(first);
        assertThat(answerRepository.findAllByInterviewIdOrderByAnswerIdAsc(interviewId))
                .extracting(AnswerEntity::getContent).contains("여기까지 답했습니다.");
        verify(feedbackDispatchService, never()).onTranscriptSaved(interviewId);
    }

    @Test
    void 완료_뒤_늦게_온_중단은_완료와_질문_PK를_바꾸지_않는다() {
        chatInterviewResultService.handleResult(record(Status.COMPLETED, "답변"));
        List<Long> first = questions().stream().map(QuestionEntity::getQuestionId).toList();

        chatInterviewResultService.handleResult(record(Status.ABANDONED, "다른 답변"));

        assertThat(interviewRepository.findById(interviewId).orElseThrow().getStatus()).isEqualTo(Status.COMPLETED);
        assertThat(questions()).extracting(QuestionEntity::getQuestionId).containsExactlyElementsOf(first);
        // 늦은 중단도 저장된 COMPLETED를 따른다. 빠진 대기 행을 채우는 호출이라 다시 불러도 중복 요청은 없다.
        verify(feedbackDispatchService, times(2)).onTranscriptSaved(interviewId);
    }

    /** 완료와 중단이 동시에 와도 처음 확정된 상태 하나만 남고, 채점 준비는 그 상태를 따른다. */
    @Test
    void 완료와_중단이_동시에_와도_먼저_확정한_상태만_남는다() {
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Void>> requests = List.of(Status.COMPLETED, Status.ABANDONED, Status.COMPLETED,
                        Status.ABANDONED).stream()
                .map(status -> CompletableFuture.runAsync(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    chatInterviewResultService.handleResult(record(status, "답변 " + status));
                }))
                .toList();

        start.countDown();
        CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).join();

        Status stored = interviewRepository.findById(interviewId).orElseThrow().getStatus();
        assertThat(stored).isIn(Status.COMPLETED, Status.ABANDONED);
        assertThat(questions()).hasSize(2);
        verify(feedbackDispatchService, times(stored == Status.COMPLETED ? 4 : 0)).onTranscriptSaved(interviewId);
    }

    @Test
    void 동시에_받아도_한_번만_저장한다() {
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Void>> requests = List.of(1, 2, 3).stream()
                .map(i -> CompletableFuture.runAsync(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    chatInterviewResultService.handleResult(record("답변 " + i));
                }))
                .toList();

        start.countDown();
        // 모두 예외 없이 끝난다. 채팅 서버는 어느 응답이든 성공으로 받는다.
        CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).join();

        assertThat(questions()).hasSize(2);
        assertThat(answerRepository.findAllByInterviewIdOrderByAnswerIdAsc(interviewId)).hasSize(2);
        verify(feedbackDispatchService, times(3)).onTranscriptSaved(interviewId);
    }

    /**
     * 처음 저장은 커밋됐는데 채점·음성 분석 준비가 빠진 경우(그 직후 서버 종료, DB 오류). 기록은 다시 저장하지 않지만
     * 다시 받은 기록으로 준비를 채운다. 스윕은 있는 대기 행만 훑으므로 이 길이 아니면 아무도 채우지 않는다.
     */
    @Test
    void 저장_뒤_채점_준비가_빠졌으면_다시_받은_기록으로_채운다() {
        doThrow(new IllegalStateException("대기 행을 만들지 못함")).doNothing()
                .when(feedbackDispatchService).onTranscriptSaved(interviewId);

        chatInterviewResultService.handleResult(record("조회가 쓰기보다 많았습니다."));
        List<Long> firstIds = questions().stream().map(QuestionEntity::getQuestionId).toList();
        chatInterviewResultService.handleResult(record("조회가 쓰기보다 많았습니다."));

        assertThat(questions()).extracting(QuestionEntity::getQuestionId).containsExactlyElementsOf(firstIds);
        verify(feedbackDispatchService, times(2)).onTranscriptSaved(interviewId);
    }

    /** 중간에 실패하면 질문까지 함께 롤백되고 면접은 진행 중으로 남는다. 다시 받은 기록이 처음부터 저장된다. */
    @Test
    void 저장_중_실패하면_다시_받은_기록을_처음부터_저장한다() {
        // 답변 내용은 NOT NULL이라 질문을 넣은 뒤 답변을 넣다가 실패한다.
        assertThatThrownBy(() -> chatInterviewResultService.handleResult(record(null)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(questions()).isEmpty();
        assertThat(interviewRepository.findById(interviewId).orElseThrow().getStatus()).isEqualTo(Status.IN_PROGRESS);

        chatInterviewResultService.handleResult(record("조회가 쓰기보다 많았습니다."));

        assertThat(questions()).hasSize(2);
        assertThat(interviewRepository.findById(interviewId).orElseThrow().getStatus()).isEqualTo(Status.COMPLETED);
        verify(feedbackDispatchService, times(1)).onTranscriptSaved(interviewId);
    }
}
