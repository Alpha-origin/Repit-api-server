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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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
    private ChatServerClient chatServerClient;

    @Captor
    private ArgumentCaptor<ChatInterviewPrepareRequest> sentRequest;

    private ChatInterviewHandoffService service;

    @BeforeEach
    void setUp() {
        service = new ChatInterviewHandoffService(interviewRepository, personaRepository, chatServerClient);

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
