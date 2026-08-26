package repit.repit_api_server.domain.userdata.feedback.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import repit.repit_api_server.domain.userdata.feedback.dto.request.FeedbackSoloRequest;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackEntity;
import repit.repit_api_server.domain.userdata.feedback.repository.FeedbackItemRepository;
import repit.repit_api_server.domain.userdata.feedback.repository.FeedbackPersonaRepository;
import repit.repit_api_server.domain.userdata.feedback.repository.FeedbackRepository;
import repit.repit_api_server.domain.userdata.interview.dto.response.ChatAnswerResponse;
import repit.repit_api_server.domain.userdata.interview.dto.response.ChatInterviewAllResponse;
import repit.repit_api_server.domain.userdata.interview.dto.response.ChatInterviewQnAResponse;
import repit.repit_api_server.domain.userdata.interview.dto.response.ChatQuestionResponse;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.persona.entity.PersonaEntity;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Gender;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Level;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Role;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Type;
import repit.repit_api_server.domain.userdata.persona.repository.PersonaRepository;
import repit.repit_api_server.global.client.AiServerClient;
import repit.repit_api_server.global.client.AuthServerClient;
import repit.repit_api_server.global.client.ChatServerClient;
import repit.repit_api_server.global.response.UserResponse;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1:1 피드백 요청을 분석 서버 본문으로 옮기는 부분.
 *
 * <p>채팅 서버 응답에 없는 값(면접관 성향·답변 번호)을 무엇으로 채우는지, 오프셋 없는 시각을
 * 어떻게 UTC로 옮기는지를 고정한다. 여기가 어긋나면 잘못된 값이 분석 서버까지 흘러간다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeedbackServiceSoloRequestTest {

    @Mock
    private FeedbackRepository feedbackRepository;
    @Mock
    private FeedbackItemRepository feedbackItemRepository;
    @Mock
    private FeedbackPersonaRepository feedbackPersonaRepository;
    @Mock
    private InterviewRepository interviewRepository;
    @Mock
    private PersonaRepository personaRepository;
    @Mock
    private ChatServerClient chatServerClient;
    @Mock
    private AiServerClient aiServerClient;
    @Mock
    private AuthServerClient authServerClient;

    private FeedbackService service;

    @BeforeEach
    void setUp() {
        service = new FeedbackService(feedbackRepository, feedbackItemRepository, feedbackPersonaRepository,
                interviewRepository, personaRepository, chatServerClient, aiServerClient, authServerClient);
        ReflectionTestUtils.setField(service, "callbackBaseUrl", "https://api.repit.test");

        UserResponse user = mock(UserResponse.class);
        when(user.getId()).thenReturn(7L);
        when(authServerClient.getUser("Bearer token")).thenReturn(user);

        when(interviewRepository.findById(3L)).thenReturn(Optional.of(InterviewEntity.builder()
                .interviewId(3L)
                .userId(7L)
                .personaId(5L)
                .mode(InterviewMode.SOLO)
                .sessionId("sess-1")
                .status(Status.COMPLETED)
                .build()));
        when(feedbackRepository.findTopByInterviewIdOrderByCreatedAtDesc(3L)).thenReturn(Optional.empty());
        when(feedbackRepository.save(any(FeedbackEntity.class))).thenAnswer(call -> call.getArgument(0));
        when(chatServerClient.getInterview("sess-1")).thenReturn(chatInterview());
    }

    private ChatInterviewAllResponse chatInterview() {
        ChatQuestionResponse question = new ChatQuestionResponse(
                31L, null, repit.repit_api_server.domain.userdata.question.entity.enums.Type.ORIGINAL,
                "도입 근거 확인", "WebFlux 를 도입한 이유가 무엇인가요?", 5L,
                LocalDateTime.parse("2026-08-18T01:00:00"));
        ChatAnswerResponse answer = new ChatAnswerResponse(
                3L, 31L, 7L, 90, "스레드가 I/O 대기에 묶였습니다.",
                LocalDateTime.parse("2026-08-18T01:01:30"));

        return new ChatInterviewAllResponse("sess-1", 3L, 7L, Status.COMPLETED, 1,
                LocalDateTime.parse("2026-08-18T01:00:00"),
                List.of(new ChatInterviewQnAResponse(question, answer)));
    }

    private PersonaEntity persona(Type type) {
        return PersonaEntity.builder()
                .personaId(5L)
                .personaName("김테크")
                .role(Role.TECH)
                .type(type)
                .level(Level.NORMAL)
                .career(7)
                .gender(Gender.MALE)
                .build();
    }

    private FeedbackSoloRequest captureRequest() {
        ArgumentCaptor<FeedbackSoloRequest> sent = ArgumentCaptor.forClass(FeedbackSoloRequest.class);
        verify(aiServerClient).requestSoloFeedback(sent.capture());
        return sent.getValue();
    }

    @Test
    void 오프셋_없는_시각을_UTC로_옮겨_보낸다() {
        when(personaRepository.findById(5L)).thenReturn(Optional.of(persona(Type.NEUTRAL)));

        service.requestFeedback("Bearer token", 3L);

        FeedbackSoloRequest request = captureRequest();
        assertThat(request.getQuestions().get(0).getCreatedAt())
                .isEqualTo(OffsetDateTime.parse("2026-08-18T01:00:00Z"));
        assertThat(request.getAnswers().get(0).getCreatedAt())
                .isEqualTo(OffsetDateTime.parse("2026-08-18T01:01:30Z"));
    }

    @Test
    void 면접관_성향은_우리_DB에서_읽는다() {
        when(personaRepository.findById(5L)).thenReturn(Optional.of(persona(Type.STRESS)));

        service.requestFeedback("Bearer token", 3L);

        assertThat(captureRequest().getPersonaType()).isEqualTo("STRESS");
    }

    @Test
    void 면접관이_여럿이면_성향_없이_보낸다() {
        when(interviewRepository.findById(3L)).thenReturn(Optional.of(InterviewEntity.builder()
                .interviewId(3L)
                .userId(7L)
                .mode(InterviewMode.MULTI)
                .sessionId("sess-1")
                .status(Status.COMPLETED)
                .build()));

        service.requestFeedback("Bearer token", 3L);

        assertThat(captureRequest().getPersonaType()).isNull();
    }

    @Test
    void 답변_번호가_없으므로_질문_번호로_채운다() {
        when(personaRepository.findById(5L)).thenReturn(Optional.of(persona(Type.NEUTRAL)));

        service.requestFeedback("Bearer token", 3L);

        FeedbackSoloRequest.Answer answer = captureRequest().getAnswers().get(0);
        assertThat(answer.getAnswerId()).isEqualTo("31");
        assertThat(answer.getQuestionId()).isEqualTo("31");
    }
}
