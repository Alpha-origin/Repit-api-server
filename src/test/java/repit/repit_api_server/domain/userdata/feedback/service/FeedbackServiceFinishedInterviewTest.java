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
import repit.repit_api_server.domain.userdata.answer.entity.AnswerEntity;
import repit.repit_api_server.domain.userdata.answer.repository.AnswerRepository;
import repit.repit_api_server.domain.userdata.feedback.dto.response.FeedbackAcceptedResponse;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackEntity;
import repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackStatus;
import repit.repit_api_server.domain.userdata.feedback.repository.FeedbackItemRepository;
import repit.repit_api_server.domain.userdata.feedback.repository.FeedbackPersonaRepository;
import repit.repit_api_server.domain.userdata.feedback.repository.FeedbackRepository;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewPersonaEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewPersonaRepository;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.persona.entity.PersonaEntity;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Gender;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Level;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Role;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Type;
import repit.repit_api_server.domain.userdata.persona.repository.PersonaRepository;
import repit.repit_api_server.domain.userdata.question.entity.QuestionEntity;
import repit.repit_api_server.domain.userdata.question.repository.QuestionRepository;
import repit.repit_api_server.global.client.AiServerClient;
import repit.repit_api_server.global.client.AuthServerClient;
import repit.repit_api_server.global.exception.BusinessException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 면접이 끝나 기록이 넘어온 직후 접수하는 채점.
 *
 * <p>채팅 서버가 부르는 길이라 사용자 토큰이 없다. 소유자를 면접에서 읽지 못하면 채점 결과가
 * 주인 없이 남아 사용자가 조회할 수 없다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeedbackServiceFinishedInterviewTest {

    @Mock
    private FeedbackRepository feedbackRepository;
    @Mock
    private FeedbackItemRepository feedbackItemRepository;
    @Mock
    private FeedbackPersonaRepository feedbackPersonaRepository;
    @Mock
    private InterviewRepository interviewRepository;
    @Mock
    private InterviewPersonaRepository interviewPersonaRepository;
    @Mock
    private PersonaRepository personaRepository;
    @Mock
    private QuestionRepository questionRepository;
    @Mock
    private AnswerRepository answerRepository;
    @Mock
    private AiServerClient aiServerClient;
    @Mock
    private AuthServerClient authServerClient;

    private FeedbackService service;

    @BeforeEach
    void setUp() {
        service = new FeedbackService(feedbackRepository, feedbackItemRepository, feedbackPersonaRepository,
                interviewRepository, interviewPersonaRepository, personaRepository, questionRepository,
                answerRepository, aiServerClient, authServerClient);
        ReflectionTestUtils.setField(service, "callbackBaseUrl", "https://api.repit.test");
        ReflectionTestUtils.setField(service, "pendingTimeout", Duration.ofMinutes(5));

        when(interviewRepository.findById(3L)).thenReturn(Optional.of(interview(Status.COMPLETED, 5L)));
        when(feedbackRepository.findTopByInterviewIdOrderByCreatedAtDesc(3L)).thenReturn(Optional.empty());
        when(feedbackRepository.save(any(FeedbackEntity.class))).thenAnswer(call -> call.getArgument(0));
        when(personaRepository.findById(5L)).thenReturn(Optional.of(persona()));
        when(questionRepository.findAllByInterviewIdOrderByQuestionIdAsc(3L)).thenReturn(questions());
        when(answerRepository.findAllByInterviewIdOrderByAnswerIdAsc(3L)).thenReturn(answers());
        when(aiServerClient.requestSoloFeedback(any()))
                .thenReturn(new FeedbackAcceptedResponse("job-1", "sess-1", "accepted", null));
    }

    private InterviewEntity interview(Status status, Long personaId) {
        return InterviewEntity.builder()
                .interviewId(3L)
                .userId(7L)
                .personaId(personaId)
                .mode(personaId == null ? InterviewMode.MULTI : InterviewMode.SOLO)
                .sessionId("sess-1")
                .status(status)
                .build();
    }

    private PersonaEntity persona() {
        return PersonaEntity.builder()
                .personaId(5L)
                .personaName("김테크")
                .role(Role.TECH)
                .type(Type.NEUTRAL)
                .level(Level.NORMAL)
                .career(7)
                .gender(Gender.MALE)
                .build();
    }

    private List<QuestionEntity> questions() {
        return List.of(QuestionEntity.builder()
                .questionId(901L)
                .interviewId(3L)
                .chatQuestionId(1L)
                .personaId(5L)
                .type(repit.repit_api_server.domain.userdata.question.entity.enums.Type.ORIGINAL)
                .intention("도입 근거 확인")
                .content("WebFlux 를 도입한 이유가 무엇인가요?")
                .createdAt(LocalDateTime.parse("2026-08-18T01:00:00"))
                .build());
    }

    private List<AnswerEntity> answers() {
        return List.of(AnswerEntity.builder()
                .answerId(501L)
                .interviewId(3L)
                .questionId(901L)
                .userId(7L)
                .responseTime(90)
                .content("스레드가 I/O 대기에 묶였습니다.")
                .createdAt(LocalDateTime.parse("2026-08-18T01:01:30"))
                .build());
    }

    private FeedbackEntity feedback(FeedbackStatus status) {
        return FeedbackEntity.builder()
                .feedbackId(11L)
                .interviewId(3L)
                .userId(7L)
                .sessionId("sess-1")
                .status(status)
                .createdAt(LocalDateTime.now())
                .build();
    }

    @Test
    void 토큰_없이도_면접에_적힌_소유자로_채점을_접수한다() {
        service.requestFeedbackForFinishedInterview(3L);

        verify(aiServerClient).requestSoloFeedback(any());
        // 사용자 조회는 토큰이 있어야 한다. 여기서 부르면 채팅 서버 호출이 401로 끝난다.
        verify(authServerClient, never()).getUser(any());

        ArgumentCaptor<FeedbackEntity> saved = ArgumentCaptor.forClass(FeedbackEntity.class);
        verify(feedbackRepository).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(7L);
        assertThat(saved.getValue().getInterviewId()).isEqualTo(3L);
        assertThat(saved.getValue().getSessionId()).isEqualTo("sess-1");
        assertThat(saved.getValue().getJobId()).isEqualTo("job-1");
        assertThat(saved.getValue().getStatus()).isEqualTo(FeedbackStatus.PENDING);
    }

    @Test
    void 면접관이_여럿이면_N대1_채점으로_보낸다() {
        when(interviewRepository.findById(3L)).thenReturn(Optional.of(interview(Status.COMPLETED, null)));
        when(interviewPersonaRepository.findAllByInterviewIdOrderByPersonaOrderAsc(3L)).thenReturn(List.of(
                InterviewPersonaEntity.builder().interviewId(3L).personaId(5L).personaOrder(0).build()));
        when(personaRepository.findAllById(List.of(5L))).thenReturn(List.of(persona()));

        service.requestFeedbackForFinishedInterview(3L);

        verify(aiServerClient).requestMultiFeedback(any());
        verify(aiServerClient, never()).requestSoloFeedback(any());
    }

    @Test
    void 중단된_면접도_답한_데까지는_채점한다() {
        when(interviewRepository.findById(3L)).thenReturn(Optional.of(interview(Status.ABANDONED, 5L)));

        service.requestFeedbackForFinishedInterview(3L);

        verify(aiServerClient).requestSoloFeedback(any());
    }

    @Test
    void 이미_채점_중이면_다시_요청하지_않는다() {
        when(feedbackRepository.findTopByInterviewIdOrderByCreatedAtDesc(3L))
                .thenReturn(Optional.of(feedback(FeedbackStatus.PENDING)));

        // 웹이 먼저 요청했거나 같은 기록이 두 번 넘어온 경우다. 거절이 아니라 건너뛰기다.
        service.requestFeedbackForFinishedInterview(3L);

        verify(aiServerClient, never()).requestSoloFeedback(any());
        verify(feedbackRepository, never()).save(any());
    }

    @Test
    void 이미_생성된_피드백이_있으면_다시_요청하지_않는다() {
        when(feedbackRepository.findTopByInterviewIdOrderByCreatedAtDesc(3L))
                .thenReturn(Optional.of(feedback(FeedbackStatus.SUCCEEDED)));

        service.requestFeedbackForFinishedInterview(3L);

        verify(aiServerClient, never()).requestSoloFeedback(any());
    }

    @Test
    void 지난_채점이_실패로_끝났으면_다시_요청한다() {
        when(feedbackRepository.findTopByInterviewIdOrderByCreatedAtDesc(3L))
                .thenReturn(Optional.of(feedback(FeedbackStatus.FAILED)));

        service.requestFeedbackForFinishedInterview(3L);

        verify(aiServerClient).requestSoloFeedback(any());
    }

    @Test
    void 채점할_답변이_없으면_분석_서버를_부르지_않는다() {
        when(answerRepository.findAllByInterviewIdOrderByAnswerIdAsc(3L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.requestFeedbackForFinishedInterview(3L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("채점할 답변이 없습니다.");

        verify(aiServerClient, never()).requestSoloFeedback(any());
    }
}
