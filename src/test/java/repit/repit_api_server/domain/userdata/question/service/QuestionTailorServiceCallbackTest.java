package repit.repit_api_server.domain.userdata.question.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import repit.repit_api_server.domain.metadata.repository.AnalysisDataRepository;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.interview.service.ChatInterviewHandoffService;
import repit.repit_api_server.domain.userdata.persona.repository.PersonaRepository;
import repit.repit_api_server.domain.userdata.question.dto.request.QuestionTailorCallbackRequest;
import repit.repit_api_server.domain.userdata.question.dto.response.TailoredQuestionResponse;
import repit.repit_api_server.domain.userdata.question.entity.QuestionTailorEntity;
import repit.repit_api_server.domain.userdata.question.entity.enums.TailorStatus;
import repit.repit_api_server.domain.userdata.question.repository.QuestionTailorRepository;
import repit.repit_api_server.global.client.AiServerClient;
import repit.repit_api_server.global.client.AuthServerClient;
import repit.repit_api_server.global.exception.ExternalApiException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuestionTailorServiceCallbackTest {

    @Mock
    private QuestionTailorRepository questionTailorRepository;
    @Mock
    private InterviewRepository interviewRepository;
    @Mock
    private PersonaRepository personaRepository;
    @Mock
    private AnalysisDataRepository analysisDataRepository;
    @Mock
    private AiServerClient aiServerClient;
    @Mock
    private AuthServerClient authServerClient;
    @Mock
    private ChatInterviewHandoffService chatInterviewHandoffService;

    @Captor
    private ArgumentCaptor<QuestionTailorEntity> savedTailor;

    private QuestionTailorService service;

    @BeforeEach
    void setUp() {
        service = new QuestionTailorService(questionTailorRepository, interviewRepository, personaRepository,
                analysisDataRepository, aiServerClient, authServerClient, chatInterviewHandoffService,
                new ObjectMapper());
    }

    private QuestionTailorEntity pendingTailor() {
        return QuestionTailorEntity.builder()
                .tailorId(1L)
                .interviewId(3L)
                .userId(7L)
                .jobId("job-1")
                .analysisJobId("analysis-1")
                .status(TailorStatus.PENDING)
                .chatDelivered(false)
                .sourceQuestions(List.of(
                        question(1, "왜 Redis 를 썼나요?"),
                        question(2, "왜 WebFlux 를 썼나요?")))
                .build();
    }

    private TailoredQuestionResponse question(int id, String content) {
        return TailoredQuestionResponse.builder()
                .id(id)
                .category("tech_choice")
                .question(content)
                .expectedAnswer("선택 근거와 대안 비교")
                .basedOn(List.of("order-api/src/cache.py"))
                .build();
    }

    /** 콜백 처리는 결과 저장과 채팅 서버 전달 기록으로 두 번 저장한다. 마지막 상태가 최종본이다. */
    private QuestionTailorEntity lastSaved() {
        verify(questionTailorRepository, atLeastOnce()).save(savedTailor.capture());
        return savedTailor.getValue();
    }

    @Test
    void 전_문항이_재작성되면_본문만_바뀌고_나머지_필드는_원질문을_유지한다() {
        QuestionTailorEntity tailor = pendingTailor();
        when(questionTailorRepository.findByJobId("job-1")).thenReturn(Optional.of(tailor));

        service.handleCallback(new QuestionTailorCallbackRequest("job-1", "3", "succeeded",
                new QuestionTailorCallbackRequest.Result(true, List.of(
                        new QuestionTailorCallbackRequest.Question(1, "다시 쓴 Redis 질문"),
                        new QuestionTailorCallbackRequest.Question(2, "다시 쓴 WebFlux 질문"))),
                null));

        QuestionTailorEntity saved = lastSaved();
        assertThat(saved.getStatus()).isEqualTo(TailorStatus.SUCCEEDED);
        assertThat(saved.getTailored()).isTrue();
        assertThat(saved.getQuestions()).extracting(TailoredQuestionResponse::getQuestion)
                .containsExactly("다시 쓴 Redis 질문", "다시 쓴 WebFlux 질문");
        // category / expectedAnswer / basedOn 은 콜백에 실려오지 않으므로 원질문 값이 남아야 한다.
        assertThat(saved.getQuestions()).extracting(TailoredQuestionResponse::getExpectedAnswer)
                .containsOnly("선택 근거와 대안 비교");
        assertThat(saved.getQuestions()).extracting(TailoredQuestionResponse::getCategory)
                .containsOnly("tech_choice");
        // 원질문도 함께 남아야 채팅 서버가 재작성 전후를 대조할 수 있다.
        assertThat(saved.getSourceQuestions()).extracting(TailoredQuestionResponse::getQuestion)
                .containsExactly("왜 Redis 를 썼나요?", "왜 WebFlux 를 썼나요?");
    }

    @Test
    void 재작성_결과가_확정되면_채팅_서버로_면접_데이터를_넘긴다() {
        QuestionTailorEntity tailor = pendingTailor();
        when(questionTailorRepository.findByJobId("job-1")).thenReturn(Optional.of(tailor));

        service.handleCallback(new QuestionTailorCallbackRequest("job-1", "3", "succeeded",
                new QuestionTailorCallbackRequest.Result(true, List.of(
                        new QuestionTailorCallbackRequest.Question(1, "다시 쓴 Redis 질문"),
                        new QuestionTailorCallbackRequest.Question(2, "다시 쓴 WebFlux 질문"))),
                null));

        verify(chatInterviewHandoffService).deliver(tailor);
        assertThat(lastSaved().getChatDelivered()).isTrue();
    }

    @Test
    void 채팅_서버_전달에_실패해도_재작성_결과는_남고_사유를_기록한다() {
        QuestionTailorEntity tailor = pendingTailor();
        when(questionTailorRepository.findByJobId("job-1")).thenReturn(Optional.of(tailor));
        doThrow(new ExternalApiException("채팅 서버에 오류가 발생했습니다.", null, null))
                .when(chatInterviewHandoffService).deliver(any());

        service.handleCallback(new QuestionTailorCallbackRequest("job-1", "3", "succeeded",
                new QuestionTailorCallbackRequest.Result(true, List.of(
                        new QuestionTailorCallbackRequest.Question(1, "다시 쓴 Redis 질문"),
                        new QuestionTailorCallbackRequest.Question(2, "다시 쓴 WebFlux 질문"))),
                null));

        QuestionTailorEntity saved = lastSaved();
        assertThat(saved.getStatus()).isEqualTo(TailorStatus.SUCCEEDED);
        assertThat(saved.getQuestions()).hasSize(2);
        assertThat(saved.getChatDelivered()).isFalse();
        assertThat(saved.getChatErrorMessage()).isEqualTo("채팅 서버에 오류가 발생했습니다.");
    }

    @Test
    void 이미_넘긴_면접은_콜백이_재전송돼도_다시_넘기지_않는다() {
        QuestionTailorEntity tailor = pendingTailor();
        tailor.setChatDelivered(true);
        when(questionTailorRepository.findByJobId("job-1")).thenReturn(Optional.of(tailor));

        service.handleCallback(new QuestionTailorCallbackRequest("job-1", "3", "succeeded",
                new QuestionTailorCallbackRequest.Result(true, List.of(
                        new QuestionTailorCallbackRequest.Question(1, "다시 쓴 Redis 질문"),
                        new QuestionTailorCallbackRequest.Question(2, "다시 쓴 WebFlux 질문"))),
                null));

        verify(chatInterviewHandoffService, never()).deliver(any());
    }

    @Test
    void 일부만_재작성되면_어조가_섞이지_않도록_전체를_원질문으로_되돌린다() {
        QuestionTailorEntity tailor = pendingTailor();
        when(questionTailorRepository.findByJobId("job-1")).thenReturn(Optional.of(tailor));

        service.handleCallback(new QuestionTailorCallbackRequest("job-1", "3", "succeeded",
                new QuestionTailorCallbackRequest.Result(true, List.of(
                        new QuestionTailorCallbackRequest.Question(1, "다시 쓴 Redis 질문"))),
                null));

        QuestionTailorEntity saved = lastSaved();
        assertThat(saved.getStatus()).isEqualTo(TailorStatus.SUCCEEDED);
        assertThat(saved.getTailored()).isFalse();
        assertThat(saved.getQuestions()).extracting(TailoredQuestionResponse::getQuestion)
                .containsExactly("왜 Redis 를 썼나요?", "왜 WebFlux 를 썼나요?");
    }

    @Test
    void tailored_false_폴백은_실패가_아니라_원질문으로_성공_처리한다() {
        QuestionTailorEntity tailor = pendingTailor();
        when(questionTailorRepository.findByJobId("job-1")).thenReturn(Optional.of(tailor));

        service.handleCallback(new QuestionTailorCallbackRequest("job-1", "3", "succeeded",
                new QuestionTailorCallbackRequest.Result(false, List.of(
                        new QuestionTailorCallbackRequest.Question(1, "왜 Redis 를 썼나요?"),
                        new QuestionTailorCallbackRequest.Question(2, "왜 WebFlux 를 썼나요?"))),
                null));

        QuestionTailorEntity saved = lastSaved();
        assertThat(saved.getStatus()).isEqualTo(TailorStatus.SUCCEEDED);
        assertThat(saved.getTailored()).isFalse();
        assertThat(saved.getErrorMessage()).isNull();
    }

    @Test
    void 실패_콜백이어도_원질문을_넘겨_면접을_열_수_있게_한다() {
        QuestionTailorEntity tailor = pendingTailor();
        when(questionTailorRepository.findByJobId("job-1")).thenReturn(Optional.of(tailor));

        service.handleCallback(new QuestionTailorCallbackRequest("job-1", "3", "failed", null,
                new QuestionTailorCallbackRequest.Error(422, "사전 정보가 없습니다.")));

        QuestionTailorEntity saved = lastSaved();
        assertThat(saved.getStatus()).isEqualTo(TailorStatus.FAILED);
        assertThat(saved.getTailored()).isFalse();
        assertThat(saved.getErrorStatusCode()).isEqualTo(422);
        assertThat(saved.getQuestions()).hasSize(2);
        // 재작성이 실패해도 원질문으로 면접은 열려야 한다.
        verify(chatInterviewHandoffService).deliver(tailor);
    }

    @Test
    void jobId로_못_찾으면_interviewId로_매칭한다() {
        QuestionTailorEntity tailor = pendingTailor();
        when(questionTailorRepository.findByJobId("job-1")).thenReturn(Optional.empty());
        when(questionTailorRepository.findTopByInterviewIdOrderByCreatedAtDesc(3L)).thenReturn(Optional.of(tailor));

        service.handleCallback(new QuestionTailorCallbackRequest("job-1", "3", "succeeded",
                new QuestionTailorCallbackRequest.Result(false, List.of()), null));

        verify(questionTailorRepository, atLeastOnce()).save(any(QuestionTailorEntity.class));
    }

    @Test
    void 알_수_없는_콜백은_저장하지_않는다() {
        when(questionTailorRepository.findByJobId("job-9")).thenReturn(Optional.empty());
        when(questionTailorRepository.findTopByInterviewIdOrderByCreatedAtDesc(99L)).thenReturn(Optional.empty());

        service.handleCallback(new QuestionTailorCallbackRequest("job-9", "99", "succeeded",
                new QuestionTailorCallbackRequest.Result(true, List.of()), null));

        verify(questionTailorRepository, never()).save(any());
        verify(chatInterviewHandoffService, never()).deliver(any());
    }
}
