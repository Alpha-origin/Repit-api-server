package repit.repit_api_server.domain.userdata.interview.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import repit.repit_api_server.domain.userdata.answer.repository.AnswerRepository;
import repit.repit_api_server.domain.userdata.interview.dto.response.InterviewPersonaSummary;
import repit.repit_api_server.domain.userdata.interview.dto.response.InterviewResponse;
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
import repit.repit_api_server.domain.userdata.persona.entity.enums.Role;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Type;
import repit.repit_api_server.domain.userdata.persona.repository.PersonaRepository;
import repit.repit_api_server.domain.userdata.question.repository.QuestionRepository;
import repit.repit_api_server.domain.userdata.question.service.QuestionTailorService;
import repit.repit_api_server.global.client.ChatServerClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 면접 조회 응답의 대표 면접관(persona).
 *
 * <p>웹 목록·상세는 성향·난이도·면접관 이름을 여기서 읽는다. 피드백이 아직 없는 면접도
 * 면접관은 정해져 있으므로 값이 나와야 한다. 1:1은 그 면접관, N:1은 진행 순서 맨 앞,
 * 면접관이 없으면 null이다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InterviewServicePersonaTest {

    @Mock
    private InterviewRepository interviewRepository;
    @Mock
    private QuestionRepository questionRepository;
    @Mock
    private ChatServerClient chatServerClient;
    @Mock
    private AnswerRepository answerRepository;
    @Mock
    private PersonaRepository personaRepository;
    @Mock
    private QuestionTailorService questionTailorService;
    @Mock
    private InterviewPersonaRepository interviewPersonaRepository;

    private InterviewService service;

    private static final Long USER_ID = 7L;

    @BeforeEach
    void setUp() {
        service = new InterviewService(interviewRepository, questionRepository, chatServerClient,
                answerRepository, personaRepository, questionTailorService,
                interviewPersonaRepository, null, null, org.mockito.Mockito.mock(InterviewJobSnapshotService.class));
    }

    @Test
    void 일대일_단건은_그_면접의_면접관을_싣는다() {
        when(interviewRepository.findById(3L)).thenReturn(Optional.of(solo(3L, 56L)));
        when(personaRepository.findById(56L)).thenReturn(Optional.of(
                persona(56L, "김도윤-4898e87d", Role.TECH, Type.FRIENDLY, Level.EASY, InterviewTone.GENTLE)));

        InterviewPersonaSummary persona = service.getInterviewById(USER_ID, 3L).getPersona();

        // 이름은 저장값 그대로다. 접미사는 화면이 뗀다.
        assertThat(persona).isEqualTo(new InterviewPersonaSummary(56L, "김도윤-4898e87d", Role.TECH,
                Type.FRIENDLY, Level.EASY, InterviewTone.GENTLE, "/img/56.svg"));
    }

    @Test
    void N대1_단건은_진행_순서_맨_앞_면접관을_싣는다() {
        when(interviewRepository.findById(4L)).thenReturn(Optional.of(multi(4L)));
        when(interviewPersonaRepository.findAllByInterviewIdOrderByPersonaOrderAsc(4L)).thenReturn(List.of(
                member(4L, 11L, 0), member(4L, 12L, 1)));
        when(personaRepository.findById(11L)).thenReturn(Optional.of(
                persona(11L, "박기술", Role.TECH, Type.METICULOUS, Level.HARD, InterviewTone.DIRECT)));

        InterviewResponse response = service.getInterviewById(USER_ID, 4L);

        assertThat(response.getPersonaIds()).containsExactly(11L, 12L);
        assertThat(response.getPersona()).isEqualTo(new InterviewPersonaSummary(11L, "박기술", Role.TECH,
                Type.METICULOUS, Level.HARD, InterviewTone.DIRECT, "/img/11.svg"));
        verify(personaRepository, never()).findById(12L);
    }

    @Test
    void 면접관이_걸려_있지_않으면_null이다() {
        when(interviewRepository.findById(3L)).thenReturn(Optional.of(solo(3L, null)));
        when(interviewRepository.findById(4L)).thenReturn(Optional.of(multi(4L)));
        when(interviewPersonaRepository.findAllByInterviewIdOrderByPersonaOrderAsc(4L)).thenReturn(List.of());

        assertThat(service.getInterviewById(USER_ID, 3L).getPersona()).isNull();
        assertThat(service.getInterviewById(USER_ID, 4L).getPersona()).isNull();
        verify(personaRepository, never()).findById(any());
    }

    @Test
    void 면접관이_지워졌으면_null이다() {
        when(interviewRepository.findById(3L)).thenReturn(Optional.of(solo(3L, 56L)));
        when(personaRepository.findById(56L)).thenReturn(Optional.empty());

        assertThat(service.getInterviewById(USER_ID, 3L).getPersona()).isNull();
    }

    /**
     * 목록은 면접관을 면접마다 읽지 않고 한 번에 읽는다. N:1 명단도 마찬가지다.
     * 1:1·N:1·면접관 없음이 섞여 있어도 각자 맞는 값이 붙어야 한다.
     */
    @Test
    void 목록은_면접관을_한_번에_읽어_면접마다_대표_면접관을_싣는다() {
        when(interviewRepository.findAllByUserId(USER_ID)).thenReturn(List.of(
                solo(1L, 56L), multi(2L), solo(3L, null), multi(4L)));
        when(interviewPersonaRepository.findAllByInterviewIdInOrderByInterviewIdAscPersonaOrderAsc(List.of(2L, 4L)))
                .thenReturn(List.of(member(2L, 11L, 0), member(2L, 12L, 1)));
        Map<Long, PersonaEntity> stored = Map.of(
                56L, persona(56L, "김도윤-4898e87d", Role.TECH, Type.FRIENDLY, Level.EASY, InterviewTone.GENTLE),
                11L, persona(11L, "박기술", Role.TECH, Type.METICULOUS, Level.HARD, InterviewTone.DIRECT),
                12L, persona(12L, "이인사", Role.HR, Type.METICULOUS, Level.HARD, InterviewTone.DIRECT));
        when(personaRepository.findAllById(anyIterable())).thenAnswer(invocation -> {
            Iterable<Long> ids = invocation.getArgument(0);
            return StreamSupport.stream(ids.spliterator(), false)
                    .map(stored::get)
                    .filter(java.util.Objects::nonNull)
                    .toList();
        });

        Map<Long, InterviewResponse> byId = service.getAllInterviewsByUserId(USER_ID).stream()
                .collect(Collectors.toMap(InterviewResponse::getInterviewId, Function.identity()));

        assertThat(byId.get(1L).getPersona().id()).isEqualTo(56L);
        assertThat(byId.get(1L).getPersona().level()).isEqualTo(Level.EASY);
        assertThat(byId.get(1L).getPersonaIds()).isEmpty();
        assertThat(byId.get(2L).getPersona().id()).isEqualTo(11L);
        assertThat(byId.get(2L).getPersonaIds()).containsExactly(11L, 12L);
        assertThat(byId.get(3L).getPersona()).isNull();
        // 명단이 비어 있는 N:1도 깨지지 않고 비어 나간다.
        assertThat(byId.get(4L).getPersona()).isNull();
        assertThat(byId.get(4L).getPersonaIds()).isEmpty();

        verify(personaRepository, times(1)).findAllById(anyIterable());
        verify(personaRepository, never()).findById(any());
        verify(interviewPersonaRepository, times(1))
                .findAllByInterviewIdInOrderByInterviewIdAscPersonaOrderAsc(any());
        verify(interviewPersonaRepository, never()).findAllByInterviewIdOrderByPersonaOrderAsc(any());
    }

    @Test
    void 대표_면접관이_하나도_없으면_면접관을_읽지_않는다() {
        when(interviewRepository.findAllByUserId(USER_ID)).thenReturn(List.of(solo(1L, null)));

        assertThat(service.getAllInterviewsByUserId(USER_ID))
                .singleElement()
                .extracting(InterviewResponse::getPersona)
                .isNull();
        verify(personaRepository, never()).findAllById(anyIterable());
        verify(interviewPersonaRepository, never())
                .findAllByInterviewIdInOrderByInterviewIdAscPersonaOrderAsc(any());
    }

    private static InterviewEntity solo(Long interviewId, Long personaId) {
        return InterviewEntity.builder()
                .interviewId(interviewId)
                .userId(USER_ID)
                .personaId(personaId)
                .mode(InterviewMode.SOLO)
                .sessionId("sess-" + interviewId)
                .status(Status.COMPLETED)
                .build();
    }

    private static InterviewEntity multi(Long interviewId) {
        return InterviewEntity.builder()
                .interviewId(interviewId)
                .userId(USER_ID)
                .mode(InterviewMode.MULTI)
                .sessionId("sess-" + interviewId)
                .status(Status.COMPLETED)
                .build();
    }

    private static InterviewPersonaEntity member(Long interviewId, Long personaId, int order) {
        return InterviewPersonaEntity.builder()
                .interviewId(interviewId)
                .personaId(personaId)
                .personaOrder(order)
                .build();
    }

    private static PersonaEntity persona(Long id, String name, Role role, Type type, Level level, InterviewTone tone) {
        return PersonaEntity.builder()
                .personaId(id)
                .personaName(name)
                .role(role)
                .type(type)
                .level(level)
                .tone(tone)
                .career(3)
                .gender(Gender.MALE)
                .imageUrl("/img/" + id + ".svg")
                .build();
    }
}
