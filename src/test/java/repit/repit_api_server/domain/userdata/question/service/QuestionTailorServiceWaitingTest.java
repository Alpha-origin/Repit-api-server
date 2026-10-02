package repit.repit_api_server.domain.userdata.question.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import repit.repit_api_server.domain.metadata.sse.SseNotifier;
import repit.repit_api_server.domain.userdata.interview.dto.response.InterviewReadyResponse;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewPersonaRepository;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.interview.service.ChatInterviewHandoffService;
import repit.repit_api_server.domain.userdata.persona.entity.PersonaEntity;
import repit.repit_api_server.domain.userdata.persona.entity.enums.InterviewTone;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Major;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Role;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Type;
import repit.repit_api_server.domain.userdata.persona.repository.PersonaRepository;
import repit.repit_api_server.domain.userdata.question.dto.request.QuestionCycleCallbackRequest;
import repit.repit_api_server.domain.userdata.question.dto.request.QuestionTailorRequest;
import repit.repit_api_server.domain.userdata.question.dto.response.QuestionTailorAcceptedResponse;
import repit.repit_api_server.domain.userdata.question.dto.response.TailoredQuestionResponse;
import repit.repit_api_server.domain.userdata.question.entity.QuestionTailorEntity;
import repit.repit_api_server.domain.userdata.question.entity.enums.TailorStatus;
import repit.repit_api_server.domain.userdata.question.preparation.FailureStage;
import repit.repit_api_server.domain.userdata.question.repository.QuestionTailorRepository;
import repit.repit_api_server.global.client.AiServerClient;
import repit.repit_api_server.global.response.UserResponse;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 꺼낼 세트가 없어 새 질문 사이클을 기다리는 면접 준비.
 *
 * <p>기다린다는 사실을 알리지 않으면 사용자는 멈춘 줄 알고, 사이클 콜백이 이어주지 않으면 대기 시간이
 * 다 지나서야 실패가 나간다. 이어줄 때는 재작성 요청에 채점 기준까지 실려야 한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuestionTailorServiceWaitingTest {

    @Mock
    private QuestionTailorRepository questionTailorRepository;
    @Mock
    private InterviewRepository interviewRepository;
    @Mock
    private InterviewPersonaRepository interviewPersonaRepository;
    @Mock
    private PersonaRepository personaRepository;
    @Mock
    private QuestionPoolService questionPoolService;
    @Mock
    private AiServerClient aiServerClient;
    @Mock
    private ChatInterviewHandoffService chatInterviewHandoffService;
    @Mock
    private SseNotifier sseNotifier;

    private QuestionTailorService service;

    private static final QuestionPoolService.DrawnSet WAITING =
            new QuestionPoolService.DrawnSet("profile-1", null, null, null);
    private static final QuestionPoolService.DrawnSet SET = new QuestionPoolService.DrawnSet("profile-1",
            List.of(TailoredQuestionResponse.builder()
                    .id(1).category("tech_choice").question("왜 Redis 를 썼나요?")
                    .intention("캐시로 Redis를 고른 이유를 설명할 수 있는지").expectedAnswer("캐시 선택 근거")
                    .basedOn(List.of()).build()),
            null, "MAJOR_BACKEND");

    @BeforeEach
    void setUp() {
        service = new QuestionTailorService(questionTailorRepository, interviewRepository,
                interviewPersonaRepository, personaRepository,
                questionPoolService, aiServerClient, chatInterviewHandoffService, sseNotifier,
                new ObjectMapper());
        ReflectionTestUtils.setField(service, "callbackBaseUrl", "https://api.test");
        ReflectionTestUtils.setField(service, "pendingTimeout", Duration.ofMinutes(2));
        ReflectionTestUtils.setField(service, "waitingTimeout", Duration.ofMinutes(3));

        // 저장하면 id가 붙는다. 이어가기는 그 id로 권리를 차지한다.
        when(questionTailorRepository.save(any(QuestionTailorEntity.class))).thenAnswer(call -> {
            QuestionTailorEntity tailor = call.getArgument(0);
            if (tailor.getTailorId() == null) {
                tailor.setTailorId(1L);
            }
            return tailor;
        });
        when(questionTailorRepository.findTopByInterviewIdOrderByCreatedAtDesc(3L)).thenReturn(Optional.empty());
        when(interviewRepository.findById(3L)).thenReturn(Optional.of(interview()));
        when(personaRepository.findById(11L)).thenReturn(Optional.of(PersonaEntity.builder()
                .personaId(11L).role(Role.TECH).major(Major.BACKEND)
                .type(Type.REALISTIC).tone(InterviewTone.DIRECT).build()));
        when(aiServerClient.tailorQuestions(any())).thenReturn(new QuestionTailorAcceptedResponse("tailor-job", "3", "accepted", null));
    }

    private InterviewEntity interview() {
        return InterviewEntity.builder()
                .interviewId(3L)
                .userId(7L)
                .personaId(11L)
                .mode(InterviewMode.SOLO)
                .sessionId("sess-1")
                .status(Status.IN_PROGRESS)
                .build();
    }

    private QuestionTailorEntity waitingTailor() {
        return QuestionTailorEntity.builder()
                .tailorId(1L)
                .interviewId(3L)
                .userId(7L)
                .mode(InterviewMode.SOLO)
                .analysisJobId("profile-1")
                .status(TailorStatus.WAITING)
                .chatDelivered(false)
                .build();
    }

    private UserResponse user() {
        UserResponse user = mock(UserResponse.class);
        when(user.getId()).thenReturn(7L);
        when(user.getMajor()).thenReturn("MAJOR_BACKEND");
        return user;
    }

    @Test
    void 꺼낼_세트가_없으면_기다리는_건을_남기고_구독에_알린다() {
        when(questionPoolService.takeSet(7L, InterviewMode.SOLO, 3L)).thenReturn(WAITING);

        QuestionTailorEntity saved = service.requestTailor(interview(), user());

        assertThat(saved.getStatus()).isEqualTo(TailorStatus.WAITING);
        // 웹은 종합 데이터 작업으로 구독해 있다. 그 자리로 알려야 받는다.
        assertThat(saved.getAnalysisJobId()).isEqualTo("profile-1");
        verify(sseNotifier).send(eq("profile-1"), eq(SseNotifier.QUESTIONS_WAITING), any(InterviewReadyResponse.class));
        verify(aiServerClient, never()).tailorQuestions(any());
    }

    /** 기다리기로 정하는 사이 사이클이 끝났다. 콜백은 이 건을 보지 못하고 지나갔으니 여기서 이어간다. */
    @Test
    void 기다리기로_한_사이_사이클이_끝났으면_곧바로_이어간다() {
        when(questionPoolService.takeSet(7L, InterviewMode.SOLO, 3L)).thenReturn(WAITING, SET);
        when(questionPoolService.settled(7L, InterviewMode.SOLO)).thenReturn(true);
        when(questionTailorRepository.claimResume(anyLong(), any())).thenReturn(1);

        QuestionTailorEntity tailor = service.requestTailor(interview(), user());

        assertThat(tailor.getStatus()).isEqualTo(TailorStatus.PENDING);
        verify(aiServerClient).tailorQuestions(any());
    }

    @Test
    void 사이클이_도착하면_기다리던_준비를_이어가고_채점_기준을_실어_보낸다() {
        QuestionTailorEntity waiting = waitingTailor();
        when(questionPoolService.applyCycleResult(any(), eq(5L)))
                .thenReturn(new QuestionPoolService.CycleOutcome(5L, 7L, InterviewMode.SOLO, true, null));
        when(questionTailorRepository.findAllByUserIdAndModeAndStatus(7L, InterviewMode.SOLO, TailorStatus.WAITING))
                .thenReturn(List.of(waiting));
        when(questionTailorRepository.claimResume(eq(1L), any())).thenReturn(1);
        when(questionPoolService.takeSet(7L, InterviewMode.SOLO, 3L)).thenReturn(SET);

        service.handleCycleCallback(QuestionCycleCallbackRequest.builder().jobId("cycle-job").status("succeeded").build(), 5L);

        assertThat(waiting.getStatus()).isEqualTo(TailorStatus.PENDING);
        assertThat(waiting.getJobId()).isEqualTo("tailor-job");
        assertThat(waiting.getSourceQuestions()).hasSize(1);

        ArgumentCaptor<QuestionTailorRequest> sent = ArgumentCaptor.forClass(QuestionTailorRequest.class);
        verify(aiServerClient).tailorQuestions(sent.capture());
        assertThat(sent.getValue().getQuestions().getFirst().getIntention())
                .isEqualTo("캐시로 Redis를 고른 이유를 설명할 수 있는지");
        // 콜백에는 사용자 정보가 없다. 전공은 종합 데이터에 실었던 값으로 대신한다.
        assertThat(sent.getValue().getProfile().getJobRole()).isEqualTo("BACKEND");
    }

    @Test
    void 다른_쪽이_먼저_이어간_준비는_건드리지_않는다() {
        when(questionPoolService.applyCycleResult(any(), eq(5L)))
                .thenReturn(new QuestionPoolService.CycleOutcome(5L, 7L, InterviewMode.SOLO, true, null));
        when(questionTailorRepository.findAllByUserIdAndModeAndStatus(7L, InterviewMode.SOLO, TailorStatus.WAITING))
                .thenReturn(List.of(waitingTailor()));
        when(questionTailorRepository.claimResume(eq(1L), any())).thenReturn(0);

        service.handleCycleCallback(QuestionCycleCallbackRequest.builder().jobId("cycle-job").status("succeeded").build(), 5L);

        verify(questionPoolService, never()).takeSet(anyLong(), any(), anyLong());
    }

    @Test
    void 사이클이_실패하면_한_번_더_요청하고_기다리던_준비는_그대로_둔다() {
        QuestionTailorEntity waiting = waitingTailor();
        when(questionPoolService.applyCycleResult(any(), eq(5L)))
                .thenReturn(new QuestionPoolService.CycleOutcome(5L, 7L, InterviewMode.SOLO, false, "500 실패"));
        when(questionTailorRepository.findAllByUserIdAndModeAndStatus(7L, InterviewMode.SOLO, TailorStatus.WAITING))
                .thenReturn(List.of(waiting));
        when(questionPoolService.retryForWaiting(5L)).thenReturn(true);

        service.handleCycleCallback(QuestionCycleCallbackRequest.builder().jobId("cycle-job").status("failed").build(), 5L);

        assertThat(waiting.getStatus()).isEqualTo(TailorStatus.WAITING);
        verify(sseNotifier, never()).sendFinal(any(), any(), any());
    }

    @Test
    void 다시_요청해도_실패하면_기다리던_준비를_실패로_알린다() {
        QuestionTailorEntity waiting = waitingTailor();
        when(questionPoolService.applyCycleResult(any(), eq(5L)))
                .thenReturn(new QuestionPoolService.CycleOutcome(5L, 7L, InterviewMode.SOLO, false, "500 실패"));
        when(questionTailorRepository.findAllByUserIdAndModeAndStatus(7L, InterviewMode.SOLO, TailorStatus.WAITING))
                .thenReturn(List.of(waiting));
        when(questionPoolService.retryForWaiting(5L)).thenReturn(false);
        when(questionTailorRepository.claimExpiration(1L)).thenReturn(1);

        service.handleCycleCallback(QuestionCycleCallbackRequest.builder().jobId("cycle-job").status("failed").build(), 5L);

        assertThat(waiting.getStatus()).isEqualTo(TailorStatus.FAILED);
        ArgumentCaptor<InterviewReadyResponse> failed = ArgumentCaptor.forClass(InterviewReadyResponse.class);
        verify(sseNotifier).sendFinal(eq("profile-1"), eq(SseNotifier.INTERVIEW_PREPARATION_FAILED), failed.capture());
        assertThat(failed.getValue().getFailureStage()).isEqualTo(FailureStage.QUESTION_GENERATION);
    }

    @Test
    void 대기_시간이_지나면_스윕이_실패로_닫는다() {
        QuestionTailorEntity waiting = waitingTailor();
        when(questionTailorRepository.findAllByStatusAndCreatedAtBefore(eq(TailorStatus.WAITING), any()))
                .thenReturn(List.of(waiting));
        when(questionTailorRepository.claimExpiration(1L)).thenReturn(1);

        service.sweepTimedOutPreparations();

        assertThat(waiting.getStatus()).isEqualTo(TailorStatus.FAILED);
        verify(sseNotifier).sendFinal(eq("profile-1"), eq(SseNotifier.INTERVIEW_PREPARATION_FAILED), any());
    }
}
