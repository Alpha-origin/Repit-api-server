package repit.repit_api_server.domain.userdata.feedback.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import repit.repit_api_server.domain.userdata.answer.repository.AnswerRepository;
import repit.repit_api_server.domain.userdata.feedback.dto.response.FeedbackResponse;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackEntity;
import repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackStatus;
import repit.repit_api_server.domain.userdata.feedback.repository.FeedbackItemRepository;
import repit.repit_api_server.domain.userdata.feedback.repository.FeedbackPersonaRepository;
import repit.repit_api_server.domain.userdata.feedback.repository.FeedbackRepository;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewPersonaRepository;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.persona.repository.PersonaRepository;
import repit.repit_api_server.domain.userdata.question.repository.QuestionRepository;
import repit.repit_api_server.global.client.AiServerClient;
import repit.repit_api_server.global.client.AuthServerClient;
import repit.repit_api_server.global.response.UserResponse;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 면접 하나에 대한 피드백 조회.
 *
 * <p>웹은 1:1과 N:1 결과를 다르게 그려야 해서, 채점 내용과 함께 그 면접이 어떤 방식이었는지도
 * 받아야 한다. 면접관 평가가 비어 있는 것만으로는 1:1인지 아직 안 온 것인지 가릴 수 없다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeedbackServiceGetOneTest {

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

        when(authServerClient.getUser(anyString())).thenReturn(user(7L));
        when(feedbackRepository.findTopByInterviewIdOrderByCreatedAtDesc(100L))
                .thenReturn(Optional.of(feedback()));
        when(feedbackPersonaRepository.findAllByFeedbackIdOrderBySortOrderAsc(10L)).thenReturn(List.of());
        when(feedbackItemRepository.findAllByFeedbackIdOrderBySortOrderAsc(10L)).thenReturn(List.of());
    }

    @Test
    void N대1_면접의_피드백은_MULTI로_나간다() {
        when(interviewRepository.findById(100L))
                .thenReturn(Optional.of(interview(InterviewMode.MULTI)));

        FeedbackResponse response = service.getFeedback("Bearer token", 100L);

        assertThat(response.getMode()).isEqualTo(InterviewMode.MULTI);
    }

    @Test
    void 일대일_면접의_피드백은_SOLO로_나간다() {
        when(interviewRepository.findById(100L))
                .thenReturn(Optional.of(interview(InterviewMode.SOLO)));

        FeedbackResponse response = service.getFeedback("Bearer token", 100L);

        assertThat(response.getMode()).isEqualTo(InterviewMode.SOLO);
    }

    @Test
    void 면접이_남아_있지_않아도_채점_결과는_그대로_준다() {
        when(interviewRepository.findById(100L)).thenReturn(Optional.empty());

        FeedbackResponse response = service.getFeedback("Bearer token", 100L);

        assertThat(response.getMode()).isNull();
        assertThat(response.getTotalScore()).isEqualTo(80);
    }

    private UserResponse user(Long id) {
        UserResponse user = new UserResponse();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private FeedbackEntity feedback() {
        return FeedbackEntity.builder()
                .feedbackId(10L)
                .interviewId(100L)
                .userId(7L)
                .sessionId("sess-100")
                .status(FeedbackStatus.SUCCEEDED)
                .totalScore(80)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private InterviewEntity interview(InterviewMode mode) {
        return InterviewEntity.builder()
                .interviewId(100L)
                .userId(7L)
                .mode(mode)
                .build();
    }
}
