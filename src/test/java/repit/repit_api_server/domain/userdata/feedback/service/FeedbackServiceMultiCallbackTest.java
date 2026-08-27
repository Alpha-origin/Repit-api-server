package repit.repit_api_server.domain.userdata.feedback.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import repit.repit_api_server.domain.userdata.feedback.dto.request.FeedbackCallbackRequest;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackEntity;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackItemEntity;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackPersonaEntity;
import repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackStatus;
import repit.repit_api_server.domain.userdata.feedback.repository.FeedbackItemRepository;
import repit.repit_api_server.domain.userdata.feedback.repository.FeedbackPersonaRepository;
import repit.repit_api_server.domain.userdata.feedback.repository.FeedbackRepository;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewPersonaRepository;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.answer.repository.AnswerRepository;
import repit.repit_api_server.domain.userdata.persona.repository.PersonaRepository;
import repit.repit_api_server.domain.userdata.question.repository.QuestionRepository;
import repit.repit_api_server.global.client.AiServerClient;
import repit.repit_api_server.global.client.AuthServerClient;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * N:1 피드백 콜백 저장.
 *
 * <p>채점을 요청하는 쪽은 언제나 이 서버다. 그래도 접수 기록이 없는 콜백이 올 수 있다 —
 * 접수는 됐는데 202 응답을 우리가 못 받으면 행이 남지 않은 채로 채점만 돌기 때문이다.
 * 그때도 결과를 받아내야 한다. 여기서 흘리면 분석 서버가 두 번 시도한 뒤 영구 폐기한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeedbackServiceMultiCallbackTest {

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

    @Captor
    private ArgumentCaptor<List<FeedbackPersonaEntity>> savedPersonas;
    @Captor
    private ArgumentCaptor<List<FeedbackItemEntity>> savedItems;

    private FeedbackService service;

    @BeforeEach
    void setUp() {
        service = new FeedbackService(feedbackRepository, feedbackItemRepository, feedbackPersonaRepository,
                interviewRepository, interviewPersonaRepository, personaRepository, questionRepository,
                answerRepository, aiServerClient, authServerClient);

        when(feedbackRepository.save(any(FeedbackEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private FeedbackCallbackRequest callback() {
        FeedbackCallbackRequest.Overall overall = new FeedbackCallbackRequest.Overall(
                72, 80, 61, "면접관이 바뀐 뒤 설명이 달라졌습니다.",
                List.of("캐시 도입 배경을 수치와 함께 설명함"),
                List.of("설명이 엇갈림"), List.of(), 6, 7);

        FeedbackCallbackRequest.Persona tech = new FeedbackCallbackRequest.Persona(
                11L, "TECH", 78, "대안 검토가 얕습니다.",
                List.of("측정값을 근거로 제시함"), List.of("탈락 이유가 없음"), 3, 3);
        FeedbackCallbackRequest.Persona hr = new FeedbackCallbackRequest.Persona(
                12L, "HR", 70, "동기가 추상적입니다.", List.of(), List.of(), 2, 2);
        FeedbackCallbackRequest.Persona ceo = new FeedbackCallbackRequest.Persona(
                13L, "CEO", 64, "우선순위 근거가 약합니다.", List.of(), List.of(), 2, 2);

        FeedbackCallbackRequest.Item item = new FeedbackCallbackRequest.Item(
                "2", 11L, "Redis를 캐시로 두신 이유는?", "기술 선택의 근거",
                "조회가 쓰기보다 많아서요.", "지연을 수치로 제시한다.",
                List.of("p99 지연을 근거로 든 점"), List.of("무효화 전략 미언급"), "부작용까지는 못 짚었습니다.");

        return new FeedbackCallbackRequest("job-1", "sess-1", "succeeded",
                new FeedbackCallbackRequest.Result(overall, List.of(tech, hr, ceo), List.of(item)), null);
    }

    @Test
    void 접수_기록이_없어도_세션으로_면접을_찾아_결과를_받아낸다() {
        when(feedbackRepository.findByJobId("job-1")).thenReturn(Optional.empty());
        when(feedbackRepository.findTopBySessionIdOrderByCreatedAtDesc("sess-1")).thenReturn(Optional.empty());
        when(interviewRepository.findBySessionId("sess-1")).thenReturn(Optional.of(InterviewEntity.builder()
                .interviewId(3L)
                .userId(7L)
                .mode(InterviewMode.MULTI)
                .sessionId("sess-1")
                .status(Status.COMPLETED)
                .build()));

        service.handleCallback(callback());

        ArgumentCaptor<FeedbackEntity> saved = ArgumentCaptor.forClass(FeedbackEntity.class);
        verify(feedbackRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());

        FeedbackEntity feedback = saved.getValue();
        assertThat(feedback.getInterviewId()).isEqualTo(3L);
        assertThat(feedback.getUserId()).isEqualTo(7L);
        assertThat(feedback.getJobId()).isEqualTo("job-1");
        assertThat(feedback.getStatus()).isEqualTo(FeedbackStatus.SUCCEEDED);
        assertThat(feedback.getReliabilityScore()).isEqualTo(61);
    }

    @Test
    void 면접관별_종합과_문항의_면접관을_저장한다() {
        when(feedbackRepository.findByJobId("job-1")).thenReturn(Optional.of(FeedbackEntity.builder()
                .feedbackId(5L)
                .interviewId(3L)
                .userId(7L)
                .sessionId("sess-1")
                .jobId("job-1")
                .status(FeedbackStatus.PENDING)
                .build()));

        service.handleCallback(callback());

        verify(feedbackPersonaRepository).saveAll(savedPersonas.capture());
        List<FeedbackPersonaEntity> personas = savedPersonas.getValue();
        assertThat(personas).hasSize(3);
        assertThat(personas).extracting(FeedbackPersonaEntity::getPersonaRole)
                .containsExactly("TECH", "HR", "CEO");
        // 분석 서버가 보낸 순서가 곧 면접 진행 순서다.
        assertThat(personas).extracting(FeedbackPersonaEntity::getSortOrder).containsExactly(0, 1, 2);
        assertThat(personas.getFirst().getFeedbackId()).isEqualTo(5L);
        assertThat(personas.getFirst().getScore()).isEqualTo(78);
        assertThat(personas.getFirst().getStrengths()).containsExactly("측정값을 근거로 제시함");

        verify(feedbackItemRepository).saveAll(savedItems.capture());
        assertThat(savedItems.getValue().getFirst().getPersonaId()).isEqualTo(11L);
    }

    @Test
    void 콜백이_다시_와도_면접관_종합이_중복되지_않는다() {
        when(feedbackRepository.findByJobId("job-1")).thenReturn(Optional.of(FeedbackEntity.builder()
                .feedbackId(5L)
                .interviewId(3L)
                .userId(7L)
                .sessionId("sess-1")
                .jobId("job-1")
                .status(FeedbackStatus.SUCCEEDED)
                .build()));

        service.handleCallback(callback());

        verify(feedbackPersonaRepository).deleteAllByFeedbackId(5L);
    }

    @Test
    void 세션도_면접도_모르면_아무것도_저장하지_않는다() {
        when(feedbackRepository.findByJobId("job-1")).thenReturn(Optional.empty());
        when(feedbackRepository.findTopBySessionIdOrderByCreatedAtDesc("sess-1")).thenReturn(Optional.empty());
        when(interviewRepository.findBySessionId("sess-1")).thenReturn(Optional.empty());

        service.handleCallback(callback());

        verify(feedbackRepository, never()).save(any());
        verify(feedbackPersonaRepository, never()).saveAll(any());
    }
}
