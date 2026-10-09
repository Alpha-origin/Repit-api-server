package repit.repit_api_server.domain.userdata.question.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import repit.repit_api_server.domain.metadata.dto.response.GenerateResponse;
import repit.repit_api_server.domain.metadata.entity.AnalysisDataEntity;
import repit.repit_api_server.domain.metadata.entity.enums.AnalysisResultType;
import repit.repit_api_server.domain.metadata.repository.AnalysisDataRepository;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.question.dto.request.QuestionCycleCallbackRequest;
import repit.repit_api_server.domain.userdata.question.dto.request.QuestionCycleRequest;
import repit.repit_api_server.domain.userdata.question.dto.response.TailoredQuestionResponse;
import repit.repit_api_server.domain.userdata.question.entity.PoolQuestionEntity;
import repit.repit_api_server.domain.userdata.question.entity.QuestionCycleEntity;
import repit.repit_api_server.domain.userdata.question.entity.enums.CycleStatus;
import repit.repit_api_server.domain.userdata.question.repository.PoolQuestionRepository;
import repit.repit_api_server.domain.userdata.question.repository.QuestionCycleRepository;
import repit.repit_api_server.global.client.AiServerClient;
import repit.repit_api_server.global.exception.BusinessException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 세트를 꺼내는 순서와 사이클이 넘어가는 시점.
 *
 * <p>2번째 세트에서 대기본을 요청하지 않으면 3번째 세트 다음 면접은 질문을 기다려야 하고, 3번째 세트에서
 * 대기본으로 넘어가지 않으면 다 쓴 사이클에 매달린다. 받은 사이클을 검증하지 않으면 면접 문항 수가 흔들린다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuestionPoolServiceTest {

    private static final Long USER_ID = 7L;
    private static final String PROFILE_JOB = "profile-1";

    @Mock
    private QuestionCycleRepository questionCycleRepository;
    @Mock
    private PoolQuestionRepository poolQuestionRepository;
    @Mock
    private AnalysisDataRepository analysisDataRepository;
    @Mock
    private AiServerClient aiServerClient;

    private QuestionPoolService service;

    @BeforeEach
    void setUp() {
        service = new QuestionPoolService(questionCycleRepository, poolQuestionRepository, analysisDataRepository,
                aiServerClient, new TransactionTemplate(mock(PlatformTransactionManager.class)));
        ReflectionTestUtils.setField(service, "callbackBaseUrl", "https://api.test");
        ReflectionTestUtils.setField(service, "generatingTimeout", Duration.ofMinutes(5));

        AnalysisDataEntity profile = AnalysisDataEntity.builder()
                .jobId(PROFILE_JOB)
                .userId(USER_ID)
                .resultType(AnalysisResultType.PROFILE)
                .result(Map.of(
                        "profile", Map.of("schemaVersion", 1, "major", "MAJOR_BACKEND"),
                        "projectSummary", Map.of("overview", "주문 처리를 맡는 백엔드")))
                .build();
        when(analysisDataRepository.findLatestCompleted(USER_ID, AnalysisResultType.PROFILE))
                .thenReturn(Optional.of(profile));
        when(analysisDataRepository.findById(PROFILE_JOB)).thenReturn(Optional.of(profile));
        when(poolQuestionRepository.findAllByInterviewIdOrderByQuestionIdAsc(anyLong())).thenReturn(List.of());
        when(questionCycleRepository.save(any(QuestionCycleEntity.class))).thenAnswer(call -> {
            QuestionCycleEntity cycle = call.getArgument(0);
            cycle.setCycleId(99L);
            return cycle;
        });
        when(aiServerClient.requestQuestionCycle(any())).thenReturn(GenerateResponse.builder().jobId("cycle-job").build());
    }

    private QuestionCycleEntity cycle(long id, int cycleNo, CycleStatus status) {
        return QuestionCycleEntity.builder()
                .cycleId(id)
                .userId(USER_ID)
                .profileJobId(PROFILE_JOB)
                .mode(InterviewMode.SOLO)
                .cycleNo(cycleNo)
                .status(status)
                .requestedAt(LocalDateTime.now())
                .build();
    }

    /** 남은 세트들. 세트마다 1:1 다섯 문항이다. */
    private List<PoolQuestionEntity> unusedSets(long cycleId, int... setNos) {
        List<PoolQuestionEntity> questions = new ArrayList<>();
        for (int setNo : setNos) {
            for (int i = 1; i <= 5; i++) {
                questions.add(PoolQuestionEntity.builder()
                        .questionId((long) setNo * 10 + i)
                        .cycleId(cycleId)
                        .setNo(setNo)
                        .category("tech_choice")
                        .question(setNo + "세트 " + i + "번 질문")
                        .intention(setNo + "세트 " + i + "번 기준")
                        .expectedAnswer("모범답안")
                        .build());
            }
        }
        return questions;
    }

    private void givenActive(QuestionCycleEntity active, List<PoolQuestionEntity> unused) {
        when(questionCycleRepository.findFirstByProfileJobIdAndModeAndStatusOrderByCycleNoAsc(
                PROFILE_JOB, InterviewMode.SOLO, CycleStatus.ACTIVE)).thenReturn(Optional.of(active));
        when(poolQuestionRepository.findAllByCycleIdAndUsedAtIsNullOrderBySetNoAscQuestionIdAsc(active.getCycleId()))
                .thenReturn(unused);
    }

    @Test
    void 가장_앞_세트를_꺼내_이_면접에_쓴_것으로_남긴다() {
        List<PoolQuestionEntity> unused = unusedSets(1L, 1, 2, 3);
        givenActive(cycle(1L, 1, CycleStatus.ACTIVE), unused);

        QuestionPoolService.DrawnSet drawn = service.takeSet(USER_ID, InterviewMode.SOLO, 3L);

        assertThat(drawn.waiting()).isFalse();
        assertThat(drawn.profileJobId()).isEqualTo(PROFILE_JOB);
        // 면접 안의 지역 번호로 다시 매긴다. 채팅 서버는 양수 번호를 원질문으로 읽는다.
        assertThat(drawn.questions()).extracting(TailoredQuestionResponse::getId).containsExactly(1, 2, 3, 4, 5);
        assertThat(drawn.questions()).extracting(TailoredQuestionResponse::getIntention)
                .containsExactly("1세트 1번 기준", "1세트 2번 기준", "1세트 3번 기준", "1세트 4번 기준", "1세트 5번 기준");
        assertThat(drawn.major()).isEqualTo("MAJOR_BACKEND");
        assertThat(unused.subList(0, 5)).allSatisfy(question -> {
            assertThat(question.getUsedAt()).isNotNull();
            assertThat(question.getInterviewId()).isEqualTo(3L);
        });
        assertThat(unused.subList(5, 15)).allSatisfy(question -> assertThat(question.getUsedAt()).isNull());
        verify(aiServerClient, never()).requestQuestionCycle(any());
    }

    @Test
    void 두_번째_세트를_꺼내면_다음_사이클을_미리_요청한다() {
        givenActive(cycle(1L, 1, CycleStatus.ACTIVE), unusedSets(1L, 2, 3));
        when(poolQuestionRepository.findRecentQuestions(eq(USER_ID), eq(InterviewMode.SOLO), eq(99L), any()))
                .thenReturn(List.of("1세트 1번 질문"));

        service.takeSet(USER_ID, InterviewMode.SOLO, 3L);

        ArgumentCaptor<QuestionCycleEntity> created = ArgumentCaptor.forClass(QuestionCycleEntity.class);
        verify(questionCycleRepository).save(created.capture());
        assertThat(created.getValue().getCycleNo()).isEqualTo(2);
        assertThat(created.getValue().getStatus()).isEqualTo(CycleStatus.GENERATING);

        ArgumentCaptor<QuestionCycleRequest> sent = ArgumentCaptor.forClass(QuestionCycleRequest.class);
        verify(aiServerClient).requestQuestionCycle(sent.capture());
        assertThat(sent.getValue().getMode()).isEqualTo("SOLO");
        assertThat(sent.getValue().getExcludeQuestions()).containsExactly("1세트 1번 질문");
        // 접수 응답보다 콜백이 먼저 와도 사이클을 찾을 수 있게 번호를 싣는다.
        assertThat(sent.getValue().getCallbackUrl())
                .isEqualTo("https://api.test/api/v1/ai/question-cycle/callback?cycleId=99&requestNo=1");
        verify(questionCycleRepository).recordJob(99L, "cycle-job");
    }

    @Test
    void 마지막_세트를_꺼내면_다_쓴_사이클을_닫고_대기본으로_넘어간다() {
        QuestionCycleEntity active = cycle(1L, 1, CycleStatus.ACTIVE);
        QuestionCycleEntity standby = cycle(2L, 2, CycleStatus.STANDBY);
        givenActive(active, unusedSets(1L, 3));
        when(questionCycleRepository.findFirstByProfileJobIdAndModeAndStatusOrderByCycleNoAsc(
                PROFILE_JOB, InterviewMode.SOLO, CycleStatus.STANDBY)).thenReturn(Optional.of(standby));

        service.takeSet(USER_ID, InterviewMode.SOLO, 3L);

        assertThat(active.getStatus()).isEqualTo(CycleStatus.EXHAUSTED);
        assertThat(standby.getStatus()).isEqualTo(CycleStatus.ACTIVE);
        verify(aiServerClient, never()).requestQuestionCycle(any());
    }

    @Test
    void 마지막_세트를_꺼냈는데_대기본이_실패해_있으면_다시_요청한다() {
        QuestionCycleEntity active = cycle(1L, 1, CycleStatus.ACTIVE);
        QuestionCycleEntity failed = cycle(2L, 2, CycleStatus.FAILED);
        failed.setJobId("old-job");
        givenActive(active, unusedSets(1L, 3));
        when(questionCycleRepository.findByProfileJobIdAndModeAndCycleNo(PROFILE_JOB, InterviewMode.SOLO, 2))
                .thenReturn(Optional.of(failed));

        service.takeSet(USER_ID, InterviewMode.SOLO, 3L);

        assertThat(failed.getStatus()).isEqualTo(CycleStatus.GENERATING);
        assertThat(failed.getJobId()).isNull();
        // 다시 요청한 차례다. 이전 요청의 콜백이 늦게 와도 이 번호로 가려낸다.
        assertThat(failed.getRequestNo()).isEqualTo(2);
        ArgumentCaptor<QuestionCycleRequest> sent = ArgumentCaptor.forClass(QuestionCycleRequest.class);
        verify(aiServerClient).requestQuestionCycle(sent.capture());
        assertThat(sent.getValue().getCallbackUrl()).endsWith("?cycleId=2&requestNo=2");
        verify(questionCycleRepository).recordJob(2L, "cycle-job");
    }

    @Test
    void 꺼낼_세트가_없고_다음_사이클이_생성_중이면_기다린다() {
        when(questionCycleRepository.findTopByProfileJobIdAndModeOrderByCycleNoDesc(PROFILE_JOB, InterviewMode.SOLO))
                .thenReturn(Optional.of(cycle(2L, 2, CycleStatus.GENERATING)));
        when(questionCycleRepository.findByProfileJobIdAndModeAndCycleNo(PROFILE_JOB, InterviewMode.SOLO, 2))
                .thenReturn(Optional.of(cycle(2L, 2, CycleStatus.GENERATING)));

        QuestionPoolService.DrawnSet drawn = service.takeSet(USER_ID, InterviewMode.SOLO, 3L);

        assertThat(drawn.waiting()).isTrue();
        assertThat(drawn.profileJobId()).isEqualTo(PROFILE_JOB);
        verify(aiServerClient, never()).requestQuestionCycle(any());
    }

    /** 기다려도 올 질문이 없으면 지금 알린다. 그러지 않으면 대기 시간이 다 지나서야 실패가 나간다. */
    @Test
    void 기다릴_사이클을_요청하지_못하면_예외로_알린다() {
        when(aiServerClient.requestQuestionCycle(any())).thenThrow(new IllegalStateException("분석 서버 응답 없음"));

        assertThatThrownBy(() -> service.takeSet(USER_ID, InterviewMode.SOLO, 3L))
                .isInstanceOf(IllegalStateException.class);
        verify(questionCycleRepository).markSendFailed(eq(99L), any(), any());
    }

    @Test
    void 준비를_다시_시도하는_면접은_이미_꺼낸_세트를_그대로_쓴다() {
        List<PoolQuestionEntity> taken = unusedSets(1L, 1);
        when(poolQuestionRepository.findAllByInterviewIdOrderByQuestionIdAsc(3L)).thenReturn(taken);
        when(questionCycleRepository.findById(1L)).thenReturn(Optional.of(cycle(1L, 1, CycleStatus.ACTIVE)));

        QuestionPoolService.DrawnSet drawn = service.takeSet(USER_ID, InterviewMode.SOLO, 3L);

        assertThat(drawn.questions()).extracting(TailoredQuestionResponse::getQuestion).containsExactly(
                "1세트 1번 질문", "1세트 2번 질문", "1세트 3번 질문", "1세트 4번 질문", "1세트 5번 질문");
        verify(poolQuestionRepository, never()).findAllByCycleIdAndUsedAtIsNullOrderBySetNoAscQuestionIdAsc(anyLong());
    }

    @Test
    void 종합_데이터가_없으면_분석을_먼저_하라고_알린다() {
        when(analysisDataRepository.findLatestCompleted(USER_ID, AnalysisResultType.PROFILE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.takeSet(USER_ID, InterviewMode.SOLO, 3L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("포트폴리오 분석을 먼저 진행해주세요")
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 지금_종합_데이터면_이전_사이클을_버리고_두_모드_사이클_1을_요청한다() {
        service.startCycles(PROFILE_JOB);

        verify(questionCycleRepository).retireOthers(USER_ID, PROFILE_JOB);
        ArgumentCaptor<QuestionCycleRequest> sent = ArgumentCaptor.forClass(QuestionCycleRequest.class);
        verify(aiServerClient, org.mockito.Mockito.times(2)).requestQuestionCycle(sent.capture());
        assertThat(sent.getAllValues()).extracting(QuestionCycleRequest::getMode).containsExactly("SOLO", "MULTI");
    }

    /**
     * 먼저 요청한 분석이 늦게 끝나거나 그 콜백이 다시 왔다. 그대로 진행하면 나중에 올린 자료로 만든 사이클을
     * 버리고, 버린 자리를 채울 사이클도 없어 그 사용자는 면접을 열 수 없다.
     */
    @Test
    void 나중에_요청한_종합_데이터가_있으면_아무것도_버리지_않는다() {
        when(analysisDataRepository.findById("profile-old")).thenReturn(Optional.of(AnalysisDataEntity.builder()
                .jobId("profile-old").userId(USER_ID).resultType(AnalysisResultType.PROFILE).result(Map.of()).build()));

        service.startCycles("profile-old");

        verify(questionCycleRepository, never()).retireOthers(any(), any());
        verify(aiServerClient, never()).requestQuestionCycle(any());
    }

    // --- 사이클 콜백 ---

    private QuestionCycleCallbackRequest succeeded(InterviewMode mode, int perSet) {
        List<QuestionCycleCallbackRequest.Question> questions = new ArrayList<>();
        for (int setNo = 1; setNo <= 3; setNo++) {
            for (int i = 0; i < perSet; i++) {
                questions.add(QuestionCycleCallbackRequest.Question.builder()
                        .setNo(setNo).category("implementation").question("질문 " + setNo + "-" + i)
                        .intention("기준 " + setNo + "-" + i).expectedAnswer("모범답안").basedOn(List.of("a.java"))
                        .build());
            }
        }
        return QuestionCycleCallbackRequest.builder()
                .jobId("cycle-job")
                .status("succeeded")
                .result(QuestionCycleCallbackRequest.Result.builder().mode(mode.name()).questions(questions).build())
                .build();
    }

    @SuppressWarnings("unchecked")
    @Test
    void 받은_사이클을_저장하고_쓰는_사이클이_없으면_바로_쓴다() {
        QuestionCycleEntity generating = cycle(5L, 1, CycleStatus.GENERATING);
        when(questionCycleRepository.lockById(5L)).thenReturn(Optional.of(generating));

        QuestionPoolService.CycleOutcome outcome = service.applyCycleResult(succeeded(InterviewMode.SOLO, 5), 5L, 1);

        assertThat(outcome.succeeded()).isTrue();
        assertThat(generating.getStatus()).isEqualTo(CycleStatus.ACTIVE);
        assertThat(generating.getJobId()).isEqualTo("cycle-job");
        ArgumentCaptor<List<PoolQuestionEntity>> saved = ArgumentCaptor.forClass(List.class);
        verify(poolQuestionRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(15);
        assertThat(saved.getValue()).allSatisfy(question -> assertThat(question.getCycleId()).isEqualTo(5L));
    }

    @Test
    void 쓰는_사이클이_있으면_대기본으로_둔다() {
        QuestionCycleEntity generating = cycle(5L, 2, CycleStatus.GENERATING);
        when(questionCycleRepository.lockById(5L)).thenReturn(Optional.of(generating));
        when(questionCycleRepository.findFirstByProfileJobIdAndModeAndStatusOrderByCycleNoAsc(
                PROFILE_JOB, InterviewMode.SOLO, CycleStatus.ACTIVE)).thenReturn(Optional.of(cycle(1L, 1, CycleStatus.ACTIVE)));

        service.applyCycleResult(succeeded(InterviewMode.SOLO, 5), 5L, 1);

        assertThat(generating.getStatus()).isEqualTo(CycleStatus.STANDBY);
    }

    /** 세트 구성이 어긋나면 면접 문항 수가 흔들린다. 그런 결과는 실패로 받는다. */
    @Test
    void 세트_구성이_맞지_않으면_실패로_받는다() {
        QuestionCycleEntity generating = cycle(5L, 1, CycleStatus.GENERATING);
        when(questionCycleRepository.lockById(5L)).thenReturn(Optional.of(generating));

        // 1:1 사이클인데 세트당 두 문항씩 왔다.
        QuestionPoolService.CycleOutcome outcome = service.applyCycleResult(succeeded(InterviewMode.MULTI, 2), 5L, 1);

        assertThat(outcome.succeeded()).isFalse();
        assertThat(generating.getStatus()).isEqualTo(CycleStatus.FAILED);
        verify(poolQuestionRepository, never()).saveAll(any());
    }

    @Test
    void 채점_기준이_빈_질문이_있으면_실패로_받는다() {
        QuestionCycleEntity generating = cycle(5L, 1, CycleStatus.GENERATING);
        when(questionCycleRepository.lockById(5L)).thenReturn(Optional.of(generating));
        QuestionCycleCallbackRequest request = succeeded(InterviewMode.SOLO, 5);
        ReflectionTestUtils.setField(request.getResult().getQuestions().getFirst(), "intention", " ");

        assertThat(service.applyCycleResult(request, 5L, 1).succeeded()).isFalse();
        assertThat(generating.getStatus()).isEqualTo(CycleStatus.FAILED);
    }

    /** 자료가 바뀌어 폐기한 사이클이다. 늦게 온 질문이 새 자료의 세트 사이에 섞이면 안 된다. */
    @Test
    void 폐기한_사이클의_콜백은_버린다() {
        when(questionCycleRepository.lockById(5L)).thenReturn(Optional.of(cycle(5L, 1, CycleStatus.RETIRED)));

        assertThat(service.applyCycleResult(succeeded(InterviewMode.SOLO, 5), 5L, 1)).isNull();
        verify(poolQuestionRepository, never()).saveAll(any());
    }

    /**
     * 다시 요청한 뒤 새 접수 응답이 오기 전이라 작업 id가 비어 있다. 그 틈에 온 이전 요청의 콜백은 요청 차례로
     * 가려낸다. 받아들이면 옛 질문이 새 결과로 저장되고, 정작 새 요청의 콜백은 버려진다.
     */
    @Test
    void 이전_차례의_콜백은_작업_id가_비어_있어도_버린다() {
        QuestionCycleEntity generating = cycle(5L, 1, CycleStatus.GENERATING);
        generating.setRequestNo(2);
        when(questionCycleRepository.lockById(5L)).thenReturn(Optional.of(generating));

        assertThat(service.applyCycleResult(succeeded(InterviewMode.SOLO, 5), 5L, 1)).isNull();
        assertThat(generating.getStatus()).isEqualTo(CycleStatus.GENERATING);
        verify(poolQuestionRepository, never()).saveAll(any());
    }

    /** 다시 요청하기 전 작업의 결과다. 지금 요청의 결과를 덮어쓰지 않는다. */
    @Test
    void 다른_작업의_콜백은_버린다() {
        QuestionCycleEntity generating = cycle(5L, 1, CycleStatus.GENERATING);
        generating.setJobId("new-job");
        when(questionCycleRepository.lockById(5L)).thenReturn(Optional.of(generating));

        assertThat(service.applyCycleResult(succeeded(InterviewMode.SOLO, 5), 5L, 1)).isNull();
        assertThat(generating.getStatus()).isEqualTo(CycleStatus.GENERATING);
    }
}
