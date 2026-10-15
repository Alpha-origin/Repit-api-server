package repit.repit_api_server.domain.userdata.question.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.util.UriComponentsBuilder;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewPersonaEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewPersonaRepository;
import repit.repit_api_server.domain.userdata.interview.service.ChatInterviewHandoffService;
import repit.repit_api_server.domain.userdata.persona.entity.PersonaEntity;
import repit.repit_api_server.domain.userdata.persona.entity.enums.InterviewTone;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Role;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Type;
import repit.repit_api_server.domain.userdata.persona.repository.PersonaRepository;
import repit.repit_api_server.domain.userdata.question.dto.request.QuestionTailorCallbackRequest;
import repit.repit_api_server.domain.userdata.question.dto.request.QuestionTailorMultiCallbackRequest;
import repit.repit_api_server.domain.userdata.question.dto.request.QuestionTailorMultiRequest;
import repit.repit_api_server.domain.userdata.question.dto.request.QuestionTailorRequest;
import repit.repit_api_server.domain.userdata.question.dto.response.QuestionTailorAcceptedResponse;
import repit.repit_api_server.domain.userdata.question.dto.response.TailoredQuestionResponse;
import repit.repit_api_server.domain.userdata.question.entity.QuestionTailorEntity;
import repit.repit_api_server.domain.userdata.question.entity.enums.TailorStatus;
import repit.repit_api_server.domain.userdata.question.repository.QuestionTailorRepository;
import repit.repit_api_server.global.client.AiServerClient;
import repit.repit_api_server.global.response.UserResponse;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 분석 서버가 접수 응답보다 결과 콜백을 먼저 보내는 경우(P-2). 실제 DB에 대고 본다.
 *
 * <p>콜백은 다른 스레드에서 처리한다. 다른 커넥션·트랜잭션이라, 분석 서버를 부르기 전에 준비 건이 커밋돼 있어야만
 * 그 건을 찾는다. 테스트 트랜잭션으로 감싸지 않고, 끝나면 이 면접의 준비 건을 지운다.
 */
@SpringBootTest
class QuestionTailorEarlyCallbackTest {

    @Autowired
    private QuestionTailorService service;
    @Autowired
    private QuestionTailorRepository questionTailorRepository;

    @MockitoBean
    private AiServerClient aiServerClient;
    @MockitoBean
    private QuestionPoolService questionPoolService;
    @MockitoBean
    private ChatInterviewHandoffService chatInterviewHandoffService;
    @MockitoBean
    private PersonaRepository personaRepository;
    @MockitoBean
    private InterviewPersonaRepository interviewPersonaRepository;

    // 다른 테스트나 로컬 데이터와 겹치지 않는 면접 id. question_tailor는 면접을 외래 키로 묶지 않는다.
    private final long interviewId = 900_000_000L + ThreadLocalRandom.current().nextInt(1_000_000);

    @AfterEach
    void cleanUp() {
        questionTailorRepository.deleteAll(questionTailorRepository.findAll().stream()
                .filter(tailor -> tailor.getInterviewId().equals(interviewId))
                .toList());
    }

    private InterviewEntity interview(InterviewMode mode) {
        return InterviewEntity.builder()
                .interviewId(interviewId)
                .userId(7L)
                .mode(mode)
                .sessionId(UUID.randomUUID().toString())
                .status(Status.IN_PROGRESS)
                .build();
    }

    private UserResponse user() {
        UserResponse user = mock(UserResponse.class);
        when(user.getId()).thenReturn(7L);
        when(user.getMajor()).thenReturn("BACKEND");
        return user;
    }

    private void givenSet(InterviewMode mode) {
        List<TailoredQuestionResponse> questions = IntStream.rangeClosed(1, QuestionPoolService.setSize(mode))
                .mapToObj(id -> TailoredQuestionResponse.builder()
                        .id(id)
                        .category("tech_choice")
                        .question("원질문 " + id)
                        .intention("확인할 것 " + id)
                        .expectedAnswer("기대 답변 " + id)
                        .basedOn(List.of())
                        .build())
                .toList();
        Map<String, Object> projectSummary = Map.of("overview", "주문 처리를 맡는 백엔드",
                "repositories", List.of(), "coreFeatures", List.of(), "techStack", List.of("Spring"));
        when(questionPoolService.takeSet(eq(7L), eq(mode), eq(interviewId)))
                .thenReturn(new QuestionPoolService.DrawnSet("analysis-1", questions, projectSummary, null));
    }

    private static Long tailorIdOf(String callbackUrl) {
        return Long.valueOf(UriComponentsBuilder.fromUriString(callbackUrl).build()
                .getQueryParams().getFirst(QuestionTailorService.TAILOR_ID_PARAM));
    }

    private QuestionTailorAcceptedResponse accepted(String jobId) {
        return new QuestionTailorAcceptedResponse(jobId, String.valueOf(interviewId), "accepted", null);
    }

    @Test
    void 일대일_콜백이_접수_응답보다_먼저_와도_결과가_남는다() {
        givenSet(InterviewMode.SOLO);
        when(aiServerClient.tailorQuestions(any())).thenAnswer(call -> {
            QuestionTailorRequest request = call.getArgument(0);
            QuestionTailorCallbackRequest callback = new QuestionTailorCallbackRequest("job-early",
                    String.valueOf(interviewId), "succeeded",
                    new QuestionTailorCallbackRequest.Result(true, IntStream.rangeClosed(1, 5)
                            .mapToObj(id -> new QuestionTailorCallbackRequest.Question(id, "다시 쓴 질문 " + id, null))
                            .toList()),
                    null);
            CompletableFuture.runAsync(() -> service.handleCallback(callback, tailorIdOf(request.getCallbackUrl())))
                    .join();
            return accepted("job-early");
        });

        QuestionTailorEntity returned = service.requestTailor(interview(InterviewMode.SOLO), user());

        // 늦게 온 접수 응답이 결과를 PENDING으로 되돌리지 않는다.
        QuestionTailorEntity saved = questionTailorRepository.findById(returned.getTailorId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(TailorStatus.SUCCEEDED);
        assertThat(saved.getTailored()).isTrue();
        assertThat(saved.getJobId()).isEqualTo("job-early");
        assertThat(saved.getQuestions()).extracting(TailoredQuestionResponse::getQuestion)
                .containsExactly("다시 쓴 질문 1", "다시 쓴 질문 2", "다시 쓴 질문 3", "다시 쓴 질문 4", "다시 쓴 질문 5");
        assertThat(saved.getChatDelivered()).isTrue();
        verify(chatInterviewHandoffService).deliver(any());
    }

    @Test
    void N대1_콜백이_접수_응답보다_먼저_와도_결과가_남는다() {
        givenSet(InterviewMode.MULTI);
        when(interviewPersonaRepository.findAllByInterviewIdOrderByPersonaOrderAsc(interviewId)).thenReturn(List.of(
                InterviewPersonaEntity.builder().interviewId(interviewId).personaId(11L).personaOrder(0).build(),
                InterviewPersonaEntity.builder().interviewId(interviewId).personaId(12L).personaOrder(1).build()));
        when(personaRepository.findAllById(List.of(11L, 12L))).thenReturn(List.of(
                PersonaEntity.builder().personaId(11L).role(Role.TECH).type(Type.REALISTIC).tone(InterviewTone.DIRECT).build(),
                PersonaEntity.builder().personaId(12L).role(Role.HR).type(Type.FRIENDLY).tone(InterviewTone.GENTLE).build()));
        when(aiServerClient.tailorQuestionsMulti(any())).thenAnswer(call -> {
            QuestionTailorMultiRequest request = call.getArgument(0);
            QuestionTailorMultiCallbackRequest callback = new QuestionTailorMultiCallbackRequest("job-early-multi",
                    String.valueOf(interviewId), "succeeded",
                    new QuestionTailorMultiCallbackRequest.Result(List.of(
                            new QuestionTailorMultiCallbackRequest.Question(1, 11L, "tech_choice", "다시 쓴 기술 질문",
                                    "확인할 것 1", null, "기대 답변 1", List.of()),
                            new QuestionTailorMultiCallbackRequest.Question(3, 12L, "motivation", "새 인사 질문",
                                    "지원 동기", null, "동기와 근거", List.of()))),
                    null);
            CompletableFuture.runAsync(() -> service.handleMultiCallback(callback, tailorIdOf(request.getCallbackUrl())))
                    .join();
            return accepted("job-early-multi");
        });

        QuestionTailorEntity returned = service.requestTailor(interview(InterviewMode.MULTI), user());

        QuestionTailorEntity saved = questionTailorRepository.findById(returned.getTailorId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(TailorStatus.SUCCEEDED);
        assertThat(saved.getJobId()).isEqualTo("job-early-multi");
        assertThat(saved.getQuestions()).extracting(TailoredQuestionResponse::getQuestion)
                .containsExactly("다시 쓴 기술 질문", "새 인사 질문");
        verify(chatInterviewHandoffService).deliver(any());
    }

    /** 접수 응답이 먼저 오는 평소 흐름. 접수 응답은 jobId만 채우고 콜백을 기다린다. */
    @Test
    void 접수_응답이_먼저_오면_jobId를_남기고_콜백을_기다린다() {
        givenSet(InterviewMode.SOLO);
        when(aiServerClient.tailorQuestions(any())).thenReturn(accepted("job-normal"));

        QuestionTailorEntity returned = service.requestTailor(interview(InterviewMode.SOLO), user());

        QuestionTailorEntity saved = questionTailorRepository.findById(returned.getTailorId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(TailorStatus.PENDING);
        assertThat(saved.getJobId()).isEqualTo("job-normal");
        assertThat(saved.getSourceQuestions()).hasSize(5);
    }

    /** 접수되지 않은 건을 남기면 면접을 다시 시작해도 그 건에 막혀 제한 시간까지 기다린다. */
    @Test
    void 분석_서버_요청이_실패하면_준비_건을_남기지_않는다() {
        givenSet(InterviewMode.SOLO);
        when(aiServerClient.tailorQuestions(any())).thenThrow(new IllegalStateException("분석 서버 연결 실패"));

        assertThatThrownBy(() -> service.requestTailor(interview(InterviewMode.SOLO), user()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(questionTailorRepository.findTopByInterviewIdOrderByCreatedAtDesc(interviewId)).isEmpty();
    }
}
