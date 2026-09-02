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
import repit.repit_api_server.domain.userdata.interview.entity.InterviewPersonaEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewPersonaRepository;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.persona.entity.PersonaEntity;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Gender;
import repit.repit_api_server.domain.userdata.persona.entity.enums.InterviewTone;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Level;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Major;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Role;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Type;
import repit.repit_api_server.domain.userdata.persona.repository.PersonaRepository;
import repit.repit_api_server.domain.userdata.question.dto.response.TailoredQuestionResponse;
import repit.repit_api_server.domain.userdata.question.entity.QuestionTailorEntity;
import repit.repit_api_server.domain.userdata.question.entity.enums.TailorStatus;
import repit.repit_api_server.global.client.ChatServerClient;
import repit.repit_api_server.global.exception.BusinessException;

import java.util.ArrayList;
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
    private InterviewPersonaRepository interviewPersonaRepository;
    @Mock
    private ChatServerClient chatServerClient;

    @Captor
    private ArgumentCaptor<ChatInterviewPrepareRequest> sentRequest;

    private ChatInterviewHandoffService service;

    @BeforeEach
    void setUp() {
        service = new ChatInterviewHandoffService(
                interviewRepository, personaRepository, interviewPersonaRepository, chatServerClient);

        when(interviewRepository.findById(3L)).thenReturn(Optional.of(interview(1L)));
        when(personaRepository.findById(1L)).thenReturn(Optional.of(persona(
                1L, "압박 면접관", Role.TECH, Major.BACKEND, Type.METICULOUS, InterviewTone.PRESSURING, Level.HARD)));
    }

    private PersonaEntity persona(
            Long personaId,
            String name,
            Role role,
            Major major,
            Type type,
            InterviewTone tone,
            Level level
    ) {
        return PersonaEntity.builder()
                .personaId(personaId)
                .personaName(name)
                .role(role)
                .major(major)
                .type(type)
                .tone(tone)
                .level(level)
                .career(10)
                .gender(Gender.MALE)
                .build();
    }

    /** N:1 면접의 면접관 등록. 진행 순서 맨 앞이 기술 면접관이다. */
    private void multiMembers(Long... personaIds) {
        List<InterviewPersonaEntity> members = new ArrayList<>();
        for (int order = 0; order < personaIds.length; order++) {
            members.add(InterviewPersonaEntity.builder()
                    .interviewPersonaId((long) order + 1)
                    .interviewId(3L)
                    .personaId(personaIds[order])
                    .personaOrder(order)
                    .build());
        }
        when(interviewPersonaRepository.findAllByInterviewIdOrderByPersonaOrderAsc(3L)).thenReturn(members);
    }

    private InterviewEntity interview(Long personaId) {
        return interview(personaId, InterviewMode.SOLO);
    }

    private InterviewEntity interview(Long personaId, InterviewMode mode) {
        return InterviewEntity.builder()
                .interviewId(3L)
                .userId(7L)
                .personaId(personaId)
                .mode(mode)
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
        // 면접 방식. 1:1은 SOLO다.
        assertThat(sent.getMode()).isEqualTo(InterviewMode.SOLO);
        // 면접관 설정 네 가지. 하나라도 비면 채팅 서버가 본문을 통째로 반려한다.
        assertThat(sent.getPersonality()).isEqualTo(Type.METICULOUS);
        assertThat(sent.getTone()).isEqualTo(InterviewTone.PRESSURING);
        assertThat(sent.getMajor()).isEqualTo(Major.BACKEND);
        assertThat(sent.getLevel()).isEqualTo(Level.HARD);

        ChatInterviewPrepareRequest.Question question = sent.getQuestions().getFirst();
        assertThat(question.getId()).isEqualTo(1L);
        assertThat(question.getQuestion()).isEqualTo("다시 쓴 Redis 질문");
        // 질문마다 면접관이 붙는다. 1:1은 모든 질문이 같은 면접관이다.
        assertThat(question.getPersonaId()).isEqualTo(1L);
        assertThat(question.getCategory()).isEqualTo("tech_choice");
        // 기대 답변과 근거는 채팅 서버가 그대로 들고 있다가 면접 기록과 함께 돌려준다.
        // 여기서 비워 보내면 채점 기준을 되찾을 길이 없다.
        assertThat(question.getExpectedAnswer()).isEqualTo("선택 근거와 대안 비교");
        assertThat(question.getBasedOn()).containsExactly("order-api/src/cache.py");
    }

    @Test
    void 원질문_폴백이어도_형태는_같게_유지한다() {
        service.deliver(tailor(false));

        verify(chatServerClient).prepareInterview(sentRequest.capture());
        ChatInterviewPrepareRequest.Question question = sentRequest.getValue().getQuestions().getFirst();

        assertThat(question.getQuestion()).isEqualTo("왜 Redis 를 썼나요?");
        assertThat(question.getCategory()).isEqualTo("tech_choice");
        assertThat(question.getExpectedAnswer()).isEqualTo("선택 근거와 대안 비교");
    }

    /**
     * 기대 답변이 없는 질문도 그대로 넘긴다.
     * 채점 기준을 분류로 대신하는 것은 기록이 돌아온 뒤의 일이다 —
     * {@code InterviewServiceSaveResultTest} 참고.
     */
    @Test
    void 기대_답변이_비어도_그대로_넘긴다() {
        QuestionTailorEntity tailor = tailor(true);
        tailor.setQuestions(List.of(TailoredQuestionResponse.builder()
                .id(1)
                .category("tech_choice")
                .question("왜 Redis 를 썼나요?")
                .build()));

        service.deliver(tailor);

        verify(chatServerClient).prepareInterview(sentRequest.capture());
        ChatInterviewPrepareRequest.Question question = sentRequest.getValue().getQuestions().getFirst();
        assertThat(question.getCategory()).isEqualTo("tech_choice");
        assertThat(question.getExpectedAnswer()).isNull();
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
        when(interviewRepository.findById(3L)).thenReturn(Optional.of(interview(null, InterviewMode.MULTI)));
        multiMembers(11L, 12L);
        when(personaRepository.findById(11L)).thenReturn(Optional.of(persona(
                11L, "기술 면접관", Role.TECH, Major.FRONTEND, Type.REALISTIC, InterviewTone.DIRECT, Level.NORMAL)));
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

    /**
     * 면접관이 교대하는 N:1임을 방식으로도 알린다.
     * personaId가 바뀌는 것만으로는 질문이 한 명에게 몰린 N:1을 1:1과 구분하지 못한다.
     */
    @Test
    void N대1_면접은_MULTI로_넘긴다() {
        when(interviewRepository.findById(3L)).thenReturn(Optional.of(interview(null, InterviewMode.MULTI)));
        multiMembers(11L, 12L);
        when(personaRepository.findById(11L)).thenReturn(Optional.of(persona(
                11L, "기술 면접관", Role.TECH, Major.FRONTEND, Type.REALISTIC, InterviewTone.DIRECT, Level.NORMAL)));
        QuestionTailorEntity tailor = tailor(true);
        tailor.setQuestions(List.of(TailoredQuestionResponse.builder()
                .id(2).personaId(11L).category("tech_choice")
                .question("왜 Redis 를 썼나요?").expectedAnswer("선택 근거와 대안 비교").build()));

        service.deliver(tailor);

        verify(chatServerClient).prepareInterview(sentRequest.capture());
        assertThat(sentRequest.getValue().getMode()).isEqualTo(InterviewMode.MULTI);
    }

    /**
     * 채팅 서버는 면접관 설정을 면접 하나에 한 벌만 받는다. N:1은 진행 순서 맨 앞,
     * 곧 기술 면접관의 값이 대표로 나간다. 뒤에 오는 면접관의 성향과 어조는 이 계약에 담기지 않는다.
     */
    @Test
    void N대1은_진행_순서_첫_면접관의_설정을_넘긴다() {
        when(interviewRepository.findById(3L)).thenReturn(Optional.of(interview(null, InterviewMode.MULTI)));
        multiMembers(11L, 12L);
        when(personaRepository.findById(11L)).thenReturn(Optional.of(persona(
                11L, "기술 면접관", Role.TECH, Major.FRONTEND, Type.REALISTIC, InterviewTone.DIRECT, Level.NORMAL)));
        when(personaRepository.findById(12L)).thenReturn(Optional.of(persona(
                12L, "인사 면접관", Role.HR, null, Type.FRIENDLY, InterviewTone.GENTLE, Level.EASY)));
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
        ChatInterviewPrepareRequest sent = sentRequest.getValue();
        assertThat(sent.getPersonality()).isEqualTo(Type.REALISTIC);
        assertThat(sent.getTone()).isEqualTo(InterviewTone.DIRECT);
        assertThat(sent.getMajor()).isEqualTo(Major.FRONTEND);
        assertThat(sent.getLevel()).isEqualTo(Level.NORMAL);
    }

    /**
     * 인사·CEO 면접관에게는 전공이 없지만 채팅 서버는 전공을 필수로 받는다.
     * 비워 보내면 면접이 아예 열리지 않아 하나를 채워 넘긴다.
     */
    @Test
    void 전공이_없는_면접관은_전공을_채워_넘긴다() {
        when(personaRepository.findById(1L)).thenReturn(Optional.of(persona(
                1L, "인사 면접관", Role.HR, null, Type.FRIENDLY, InterviewTone.GENTLE, Level.EASY)));

        service.deliver(tailor(true));

        verify(chatServerClient).prepareInterview(sentRequest.capture());
        ChatInterviewPrepareRequest sent = sentRequest.getValue();
        assertThat(sent.getMajor()).isEqualTo(Major.BACKEND);
        // 나머지 셋은 면접관 값 그대로다.
        assertThat(sent.getPersonality()).isEqualTo(Type.FRIENDLY);
        assertThat(sent.getTone()).isEqualTo(InterviewTone.GENTLE);
        assertThat(sent.getLevel()).isEqualTo(Level.EASY);
    }

    /** N:1인데 면접관이 등록돼 있지 않으면 설정을 채울 수 없다. 빈 값으로 열지 않고 멈춘다. */
    @Test
    void N대1에_면접관이_없으면_넘기지_않는다() {
        when(interviewRepository.findById(3L)).thenReturn(Optional.of(interview(null, InterviewMode.MULTI)));
        when(interviewPersonaRepository.findAllByInterviewIdOrderByPersonaOrderAsc(3L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.deliver(tailor(true)))
                .isInstanceOf(BusinessException.class);

        verify(chatServerClient, never()).prepareInterview(any());
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
