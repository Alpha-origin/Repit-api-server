package repit.repit_api_server.domain.userdata.interview.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import repit.repit_api_server.domain.metadata.entity.AnalysisDataEntity;
import repit.repit_api_server.domain.metadata.repository.AnalysisDataRepository;
import repit.repit_api_server.domain.userdata.interview.dto.request.ChatInterviewPrepareRequest;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.persona.entity.PersonaEntity;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Gender;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Level;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Major;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Type;
import repit.repit_api_server.domain.userdata.persona.repository.PersonaRepository;
import repit.repit_api_server.domain.userdata.question.dto.response.TailoredQuestionResponse;
import repit.repit_api_server.domain.userdata.question.entity.QuestionTailorEntity;
import repit.repit_api_server.domain.userdata.question.entity.enums.TailorStatus;
import repit.repit_api_server.global.client.ChatServerClient;
import repit.repit_api_server.global.exception.BusinessException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 채팅 서버는 분석 결과를 따로 가져가지 않는다. 면접에 필요한 값이 전부 실려 나가는지 확인한다. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatInterviewHandoffServiceTest {

    @Mock
    private InterviewRepository interviewRepository;
    @Mock
    private PersonaRepository personaRepository;
    @Mock
    private AnalysisDataRepository analysisDataRepository;
    @Mock
    private ChatServerClient chatServerClient;

    @Captor
    private ArgumentCaptor<ChatInterviewPrepareRequest> sentRequest;

    private ChatInterviewHandoffService service;

    @BeforeEach
    void setUp() {
        service = new ChatInterviewHandoffService(
                interviewRepository, personaRepository, analysisDataRepository, chatServerClient);

        when(interviewRepository.findById(3L)).thenReturn(Optional.of(InterviewEntity.builder()
                .interviewId(3L)
                .userId(7L)
                .personaId(1L)
                .sessionId("sess-1")
                .status(Status.IN_PROGRESS)
                .build()));
        when(personaRepository.findById(1L)).thenReturn(Optional.of(PersonaEntity.builder()
                .personaId(1L)
                .personaName("압박 면접관")
                .major(Major.BACKEND)
                .type(Type.STRESS)
                .level(Level.HARD)
                .career(10)
                .gender(Gender.MALE)
                .build()));
    }

    private QuestionTailorEntity tailor(boolean tailored) {
        return QuestionTailorEntity.builder()
                .tailorId(1L)
                .interviewId(3L)
                .userId(7L)
                .jobId("tailor-job-1")
                .analysisJobId("analysis-1")
                .status(TailorStatus.SUCCEEDED)
                .tailored(tailored)
                .sourceQuestions(List.of(question(1, "왜 Redis 를 썼나요?")))
                .questions(List.of(question(1, tailored ? "다시 쓴 Redis 질문" : "왜 Redis 를 썼나요?")))
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

    @Test
    void 면접_페르소나_질문이_모두_실려_나간다() {
        service.deliver(tailor(true));

        verify(chatServerClient).prepareInterview(sentRequest.capture());
        ChatInterviewPrepareRequest sent = sentRequest.getValue();

        assertThat(sent.getSessionId()).isEqualTo("sess-1");
        assertThat(sent.getInterviewId()).isEqualTo(3L);
        assertThat(sent.getUserId()).isEqualTo(7L);
        assertThat(sent.getStatus()).isEqualTo(Status.IN_PROGRESS);
        assertThat(sent.getJobId()).isEqualTo("analysis-1");
        assertThat(sent.isTailored()).isTrue();

        assertThat(sent.getPersona().getPersonaName()).isEqualTo("압박 면접관");
        assertThat(sent.getPersona().getType()).isEqualTo(Type.STRESS);
        // 난이도를 안 넘기면 채팅 서버가 꼬리질문 깊이를 판단할 근거가 없다.
        assertThat(sent.getPersona().getLevel()).isEqualTo(Level.HARD);
        assertThat(sent.getPersona().getCareer()).isEqualTo(10);

        ChatInterviewPrepareRequest.Question question = sent.getQuestions().getFirst();
        assertThat(question.getQuestion()).isEqualTo("다시 쓴 Redis 질문");
        // 원질문을 함께 넘겨야 채팅 서버가 재작성 전후를 대조할 수 있다.
        assertThat(question.getOriginalQuestion()).isEqualTo("왜 Redis 를 썼나요?");
        assertThat(question.getExpectedAnswer()).isEqualTo("선택 근거와 대안 비교");
        assertThat(question.getBasedOn()).containsExactly("order-api/src/cache.py");
    }

    /** 분석 jobId 없이 넘기면 채팅 서버의 결과 조회가 통째로 result: null이 된다. */
    @Test
    void 분석_jobId가_비어있으면_최근_분석으로_채워_넘긴다() {
        when(analysisDataRepository.findTopByUserIdAndResultIsNotNullOrderByCreatedAtDesc(7L))
                .thenReturn(Optional.of(AnalysisDataEntity.builder()
                        .jobId("analysis-latest")
                        .userId(7L)
                        .result(Map.of("interview", List.of()))
                        .build()));

        QuestionTailorEntity tailor = tailor(true);
        tailor.setAnalysisJobId(null);

        service.deliver(tailor);

        verify(chatServerClient).prepareInterview(sentRequest.capture());
        assertThat(sentRequest.getValue().getJobId()).isEqualTo("analysis-latest");
        // 다음 전달에서 다시 찾지 않도록 재작성 건에도 남긴다.
        assertThat(tailor.getAnalysisJobId()).isEqualTo("analysis-latest");
    }

    @Test
    void 채울_분석조차_없으면_빈_jobId로_넘기지_않는다() {
        when(analysisDataRepository.findTopByUserIdAndResultIsNotNullOrderByCreatedAtDesc(7L))
                .thenReturn(Optional.empty());

        QuestionTailorEntity tailor = tailor(true);
        tailor.setAnalysisJobId("  ");

        assertThatThrownBy(() -> service.deliver(tailor))
                .isInstanceOf(BusinessException.class);

        verify(chatServerClient, never()).prepareInterview(any());
    }

    @Test
    void 원질문_폴백이어도_형태는_같게_유지한다() {
        service.deliver(tailor(false));

        verify(chatServerClient).prepareInterview(sentRequest.capture());
        ChatInterviewPrepareRequest sent = sentRequest.getValue();

        assertThat(sent.isTailored()).isFalse();
        ChatInterviewPrepareRequest.Question question = sent.getQuestions().getFirst();
        assertThat(question.getQuestion()).isEqualTo("왜 Redis 를 썼나요?");
        assertThat(question.getOriginalQuestion()).isEqualTo("왜 Redis 를 썼나요?");
    }
}
