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
import repit.repit_api_server.global.exception.BusinessException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 채팅 서버가 받는 형태 그대로 나가는지 확인한다. 하나라도 비면 본문이 통째로 반려된다. */
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

        when(interviewRepository.findById(3L)).thenReturn(Optional.of(interview(1L)));
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

    private InterviewEntity interview(Long personaId) {
        return InterviewEntity.builder()
                .interviewId(3L)
                .userId(7L)
                .personaId(personaId)
                .sessionId("sess-1")
                .status(Status.IN_PROGRESS)
                .build();
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
    void 면접과_질문이_채팅_서버_형태로_나간다() {
        service.deliver(tailor(true));

        verify(chatServerClient).prepareInterview(sentRequest.capture());
        ChatInterviewPrepareRequest sent = sentRequest.getValue();

        assertThat(sent.getSessionId()).isEqualTo("sess-1");
        assertThat(sent.getInterviewId()).isEqualTo(3L);
        assertThat(sent.getUserId()).isEqualTo(7L);
        // 상태가 비면 채팅 서버가 본문을 통째로 반려한다.
        assertThat(sent.getStatus()).isEqualTo(Status.IN_PROGRESS);

        ChatInterviewPrepareRequest.Question question = sent.getQuestions().getFirst();
        assertThat(question.getId()).isEqualTo(1L);
        assertThat(question.getQuestion()).isEqualTo("다시 쓴 Redis 질문");
        // 질문마다 면접관이 붙는다. 1:1은 모든 질문이 같은 면접관이다.
        assertThat(question.getPersonaId()).isEqualTo(1L);
        // 의도는 채팅 서버가 category로 읽어 두었다가, 피드백 요청에 실어 분석 서버로 돌려보낸다.
        assertThat(question.getCategory()).isEqualTo("선택 근거와 대안 비교");
    }

    @Test
    void 원질문_폴백이어도_형태는_같게_유지한다() {
        service.deliver(tailor(false));

        verify(chatServerClient).prepareInterview(sentRequest.capture());
        ChatInterviewPrepareRequest.Question question = sentRequest.getValue().getQuestions().getFirst();

        assertThat(question.getQuestion()).isEqualTo("왜 Redis 를 썼나요?");
        assertThat(question.getCategory()).isEqualTo("선택 근거와 대안 비교");
    }

    /** 의도가 비면 피드백 단계에서 되찾을 길이 없다. 분류라도 넘긴다. */
    @Test
    void 기대_답변이_비면_분류를_의도로_넘긴다() {
        QuestionTailorEntity tailor = tailor(true);
        tailor.setQuestions(List.of(TailoredQuestionResponse.builder()
                .id(1)
                .category("tech_choice")
                .question("왜 Redis 를 썼나요?")
                .build()));

        service.deliver(tailor);

        verify(chatServerClient).prepareInterview(sentRequest.capture());
        assertThat(sentRequest.getValue().getQuestions().getFirst().getCategory()).isEqualTo("tech_choice");
    }

    @Test
    void 페르소나를_찾지_못하면_넘기지_않는다() {
        when(personaRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deliver(tailor(true)))
                .isInstanceOf(BusinessException.class);

        verify(chatServerClient, never()).prepareInterview(any());
    }

    /**
     * N:1 면접은 면접관이 여럿이라 interview.personaId가 비어 있다.
     * 대신 질문마다 면접관이 붙어 오고, 프론트는 그 값이 바뀌는 것으로 면접관 전환을 감지한다.
     */
    @Test
    void N대1은_질문에_붙어온_면접관을_그대로_넘긴다() {
        when(interviewRepository.findById(3L)).thenReturn(Optional.of(interview(null)));
        QuestionTailorEntity tailor = tailor(true);
        tailor.setQuestions(List.of(
                TailoredQuestionResponse.builder()
                        .id(2).personaId(11L).category("tech_choice")
                        .question("왜 Redis 를 썼나요?").expectedAnswer("선택 근거와 대안 비교").build(),
                TailoredQuestionResponse.builder()
                        .id(6).personaId(12L).category("motivation")
                        .question("팀에서 갈등이 있었다면?").expectedAnswer("협업 태도").build()));

        service.deliver(tailor);

        verify(chatServerClient).prepareInterview(sentRequest.capture());
        assertThat(sentRequest.getValue().getQuestions())
                .extracting(ChatInterviewPrepareRequest.Question::getPersonaId)
                .containsExactly(11L, 12L);
    }

    @Test
    void 면접관이_없으면_넘기지_않는다() {
        when(interviewRepository.findById(3L)).thenReturn(Optional.of(interview(null)));

        assertThatThrownBy(() -> service.deliver(tailor(true)))
                .isInstanceOf(BusinessException.class);

        verify(chatServerClient, never()).prepareInterview(any());
    }

    @Test
    void 본문이_빈_질문은_넘기지_않는다() {
        QuestionTailorEntity tailor = tailor(true);
        tailor.setQuestions(List.of(question(1, "  ")));

        assertThatThrownBy(() -> service.deliver(tailor))
                .isInstanceOf(BusinessException.class);

        verify(chatServerClient, never()).prepareInterview(any());
    }

    @Test
    void 넘길_질문이_없으면_넘기지_않는다() {
        QuestionTailorEntity tailor = tailor(true);
        tailor.setQuestions(List.of());
        tailor.setSourceQuestions(List.of());

        assertThatThrownBy(() -> service.deliver(tailor))
                .isInstanceOf(BusinessException.class);

        verify(chatServerClient, never()).prepareInterview(any());
    }
}
