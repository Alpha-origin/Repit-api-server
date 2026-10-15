package repit.repit_api_server.domain.userdata.question.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewPersonaEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewPersonaRepository;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.interview.service.ChatInterviewHandoffService;
import repit.repit_api_server.domain.userdata.persona.entity.PersonaEntity;
import repit.repit_api_server.domain.userdata.persona.entity.enums.InterviewTone;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Role;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Type;
import repit.repit_api_server.domain.userdata.persona.repository.PersonaRepository;
import repit.repit_api_server.domain.userdata.question.dto.request.QuestionCycleCallbackRequest;
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
import java.util.Optional;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
    @MockitoBean
    private InterviewRepository interviewRepository;

    // 다른 테스트나 로컬 데이터와 겹치지 않는 면접·사용자 id. question_tailor는 둘 다 외래 키로 묶지 않는다.
    // 사용자도 따로 둔다 — 사이클 콜백은 사용자의 기다리는 준비를 모두 이어간다.
    private final long interviewId = 900_000_000L + ThreadLocalRandom.current().nextInt(1_000_000);
    private final long userId = 900_000_000L + ThreadLocalRandom.current().nextInt(1_000_000);

    @AfterEach
    void cleanUp() {
        questionTailorRepository.deleteAll(questionTailorRepository.findAll().stream()
                .filter(tailor -> tailor.getInterviewId().equals(interviewId))
                .toList());
    }

    private InterviewEntity interview(InterviewMode mode) {
        return InterviewEntity.builder()
                .interviewId(interviewId)
                .userId(userId)
                .mode(mode)
                .sessionId(UUID.randomUUID().toString())
                .status(Status.IN_PROGRESS)
                .build();
    }

    private UserResponse user() {
        UserResponse user = mock(UserResponse.class);
        when(user.getId()).thenReturn(userId);
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
        when(questionPoolService.takeSet(eq(userId), eq(mode), eq(interviewId)))
                // 이어가기 경로에는 사용자 정보가 없어 종합 데이터에 실었던 전공을 쓴다.
                .thenReturn(new QuestionPoolService.DrawnSet("analysis-1", questions, projectSummary, "BACKEND"));
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

    // ---- 콜백과 실패 처리가 같은 건을 두고 겹칠 때 ----

    private QuestionTailorEntity pendingRow() {
        return questionTailorRepository.save(QuestionTailorEntity.builder()
                .interviewId(interviewId)
                .userId(userId)
                .mode(InterviewMode.SOLO)
                .status(TailorStatus.PENDING)
                .chatDelivered(false)
                .sourceQuestions(List.of(TailoredQuestionResponse.builder().id(1).question("원질문 1").build()))
                .build());
    }

    /** 콜백이 읽어 둔 결과. 이 건이 PENDING일 때 계산했다. */
    private static QuestionTailorEntity succeeded(QuestionTailorEntity read) {
        read.setStatus(TailorStatus.SUCCEEDED);
        read.setTailored(true);
        read.setJobId("job-cross");
        read.setQuestions(List.of(TailoredQuestionResponse.builder().id(1).question("다시 쓴 질문 1").build()));
        return read;
    }

    /** 콜백이 PENDING을 읽은 뒤 실패 처리가 먼저 차지했다. 콜백은 아무것도 쓰지 못하고 실패 처리가 확정한다. */
    @Test
    void 실패_처리가_먼저_차지하면_콜백은_결과를_쓰지_못한다() {
        QuestionTailorEntity read = pendingRow();

        assertThat(questionTailorRepository.claimExpiration(read.getTailorId())).isEqualTo(1);
        assertThat(questionTailorRepository.settleCallback(succeeded(read))).isZero();

        QuestionTailorEntity saved = questionTailorRepository.findById(read.getTailorId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(TailorStatus.FAILED);
        assertThat(saved.getQuestions()).isNull();
    }

    /** 콜백이 먼저 확정했다. 실패 처리는 차지하지 못해 낡은 엔티티로 결과를 덮지 않는다. */
    @Test
    void 콜백이_먼저_확정하면_실패_처리는_차지하지_못한다() {
        QuestionTailorEntity read = pendingRow();

        assertThat(questionTailorRepository.settleCallback(succeeded(read))).isEqualTo(1);
        assertThat(questionTailorRepository.claimExpiration(read.getTailorId())).isZero();

        QuestionTailorEntity saved = questionTailorRepository.findById(read.getTailorId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(TailorStatus.SUCCEEDED);
        assertThat(saved.getJobId()).isEqualTo("job-cross");
        assertThat(saved.getQuestions()).extracting(TailoredQuestionResponse::getQuestion).containsExactly("다시 쓴 질문 1");
    }

    /**
     * 사이클 대기에서 이어가다 콜백이 먼저 와서 결과를 확정·전달하고, 접수 요청은 뒤늦게 실패한 경우.
     * 실패 처리가 결과를 FAILED·질문 없음으로 덮으면 이미 채팅 서버로 넘긴 질문이 DB에서 사라진다.
     */
    @Test
    void 이어가다_콜백이_먼저_확정한_뒤_요청이_실패해도_결과가_남는다() {
        QuestionTailorEntity waiting = questionTailorRepository.save(QuestionTailorEntity.builder()
                .interviewId(interviewId)
                .userId(userId)
                .mode(InterviewMode.SOLO)
                .analysisJobId("analysis-1")
                .status(TailorStatus.WAITING)
                .chatDelivered(false)
                .build());

        givenSet(InterviewMode.SOLO);
        when(questionPoolService.applyCycleResult(any(), eq(5L), eq(1)))
                .thenReturn(new QuestionPoolService.CycleOutcome(5L, userId, InterviewMode.SOLO, true, false));
        when(interviewRepository.findById(interviewId)).thenReturn(Optional.of(interview(InterviewMode.SOLO)));
        when(aiServerClient.tailorQuestions(any())).thenAnswer(call -> {
            QuestionTailorRequest request = call.getArgument(0);
            QuestionTailorCallbackRequest callback = new QuestionTailorCallbackRequest("job-resume",
                    String.valueOf(interviewId), "succeeded",
                    new QuestionTailorCallbackRequest.Result(true, IntStream.rangeClosed(1, 5)
                            .mapToObj(id -> new QuestionTailorCallbackRequest.Question(id, "다시 쓴 질문 " + id, null))
                            .toList()),
                    null);
            CompletableFuture.runAsync(() -> service.handleCallback(callback, tailorIdOf(request.getCallbackUrl())))
                    .join();
            throw new IllegalStateException("접수 응답 읽기 시간 초과");
        });

        service.handleCycleCallback(
                QuestionCycleCallbackRequest.builder().jobId("cycle-job").status("succeeded").build(), 5L, 1);

        QuestionTailorEntity saved = questionTailorRepository.findById(waiting.getTailorId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(TailorStatus.SUCCEEDED);
        assertThat(saved.getQuestions()).hasSize(5);
        assertThat(saved.getChatDelivered()).isTrue();
        verify(chatInterviewHandoffService).deliver(any());
    }

    // ---- HTTP 콜백(open-in-view) ----

    @Autowired
    private WebApplicationContext webApplicationContext;

    /**
     * 실제 콜백은 HTTP로 온다. open-in-view가 켜져 있어 요청 동안 조회한 준비 건이 관리 상태로 남는다. 그 엔티티를 고친 뒤
     * 조건부 확정을 부르면 쿼리 전에 변경이 먼저 flush돼, 확정이 0건으로 끝나고 채팅 서버 전달이 빠진다.
     */
    private void postCallback(String query, String jobId) throws Exception {
        QuestionTailorCallbackRequest callback = new QuestionTailorCallbackRequest(jobId, String.valueOf(interviewId),
                "succeeded", new QuestionTailorCallbackRequest.Result(true,
                List.of(new QuestionTailorCallbackRequest.Question(1, "다시 쓴 질문 1", null))), null);
        MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
                .perform(post("/api/questions/tailor/callback" + query)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(callback)))
                .andExpect(status().isOk());
    }

    @Test
    void HTTP_콜백이_tailorId로_결과를_확정하고_넘긴다() throws Exception {
        QuestionTailorEntity row = pendingRow();

        postCallback("?tailorId=" + row.getTailorId(), "job-http");

        QuestionTailorEntity saved = questionTailorRepository.findById(row.getTailorId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(TailorStatus.SUCCEEDED);
        assertThat(saved.getQuestions()).extracting(TailoredQuestionResponse::getQuestion).containsExactly("다시 쓴 질문 1");
        assertThat(saved.getChatDelivered()).isTrue();
        verify(chatInterviewHandoffService).deliver(any());
    }

    /** 배포 전에 나간 요청의 콜백. jobId로 찾은 엔티티도 요청 동안 관리 상태로 남는다. */
    @Test
    void 배포_전_HTTP_콜백도_jobId로_결과를_확정하고_넘긴다() throws Exception {
        QuestionTailorEntity row = pendingRow();
        row.setJobId("job-http-legacy-" + interviewId);
        questionTailorRepository.save(row);

        postCallback("", "job-http-legacy-" + interviewId);

        QuestionTailorEntity saved = questionTailorRepository.findById(row.getTailorId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(TailorStatus.SUCCEEDED);
        assertThat(saved.getQuestions()).extracting(TailoredQuestionResponse::getQuestion).containsExactly("다시 쓴 질문 1");
        verify(chatInterviewHandoffService).deliver(any());
    }
}
