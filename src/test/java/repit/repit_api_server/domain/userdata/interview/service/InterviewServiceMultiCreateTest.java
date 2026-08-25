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
import org.springframework.http.HttpStatus;
import repit.repit_api_server.domain.userdata.answer.repository.AnswerRepository;
import repit.repit_api_server.domain.userdata.interview.dto.request.CreateInterviewRequest;
import repit.repit_api_server.domain.userdata.interview.dto.response.InterviewResponse;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewPersonaEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewPersonaRepository;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.persona.entity.PersonaEntity;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Gender;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Major;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Role;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Type;
import repit.repit_api_server.domain.userdata.persona.repository.PersonaRepository;
import repit.repit_api_server.domain.userdata.question.repository.QuestionRepository;
import repit.repit_api_server.domain.userdata.question.service.QuestionTailorService;
import repit.repit_api_server.global.client.AuthServerClient;
import repit.repit_api_server.global.client.ChatServerClient;
import repit.repit_api_server.global.exception.BusinessException;
import repit.repit_api_server.global.response.UserResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** N:1 면접은 기술·인사·CEO 한 명씩이고, 진행 순서는 요청 순서가 아니라 직책 순서다. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InterviewServiceMultiCreateTest {

    @Mock
    private InterviewRepository interviewRepository;
    @Mock
    private QuestionRepository questionRepository;
    @Mock
    private ChatServerClient chatServerClient;
    @Mock
    private AuthServerClient authServerClient;
    @Mock
    private AnswerRepository answerRepository;
    @Mock
    private PersonaRepository personaRepository;
    @Mock
    private QuestionTailorService questionTailorService;
    @Mock
    private InterviewPersonaRepository interviewPersonaRepository;

    @Captor
    private ArgumentCaptor<List<InterviewPersonaEntity>> savedMembers;

    private InterviewService service;

    @BeforeEach
    void setUp() {
        service = new InterviewService(interviewRepository, questionRepository, chatServerClient,
                authServerClient, answerRepository, personaRepository, questionTailorService,
                interviewPersonaRepository);

        UserResponse user = mock(UserResponse.class);
        when(user.getId()).thenReturn(7L);
        when(authServerClient.getUser("Bearer t")).thenReturn(user);

        when(interviewRepository.save(any(InterviewEntity.class))).thenAnswer(invocation -> {
            InterviewEntity interview = invocation.getArgument(0);
            interview.setInterviewId(3L);
            return interview;
        });
    }

    private PersonaEntity persona(long id, Role role) {
        return PersonaEntity.builder()
                .personaId(id)
                .personaName("면접관 " + id)
                .role(role)
                .major(role == Role.TECH ? Major.BACKEND : null)
                .type(Type.NEUTRAL)
                .career(8)
                .gender(Gender.FEMALE)
                .build();
    }

    @Test
    void 면접관은_직책_순서로_배치된다() {
        // 요청은 CEO -> 기술 -> 인사 순서로 왔다.
        when(personaRepository.findAllById(List.of(13L, 11L, 12L))).thenReturn(List.of(
                persona(13L, Role.CEO), persona(11L, Role.TECH), persona(12L, Role.HR)));

        InterviewResponse response = service.createInterview("Bearer t",
                new CreateInterviewRequest(null, null, List.of(13L, 11L, 12L)));

        assertThat(response.getMode()).isEqualTo(InterviewMode.MULTI);
        // 면접관이 여럿이라 단일 personaId는 비워 둔다.
        assertThat(response.getPersonaId()).isNull();
        assertThat(response.getPersonaIds()).containsExactly(11L, 12L, 13L);

        verify(interviewPersonaRepository).saveAll(savedMembers.capture());
        assertThat(savedMembers.getValue()).extracting(InterviewPersonaEntity::getPersonaId)
                .containsExactly(11L, 12L, 13L);
        assertThat(savedMembers.getValue()).extracting(InterviewPersonaEntity::getPersonaOrder)
                .containsExactly(0, 1, 2);
        assertThat(savedMembers.getValue().getFirst().getInterviewId()).isEqualTo(3L);
    }

    @Test
    void 직책이_빠지면_422다() {
        when(personaRepository.findAllById(List.of(11L, 12L))).thenReturn(List.of(
                persona(11L, Role.TECH), persona(12L, Role.HR)));

        assertThatThrownBy(() -> service.createInterview("Bearer t",
                new CreateInterviewRequest(null, null, List.of(11L, 12L))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        verify(interviewRepository, never()).save(any());
    }

    @Test
    void 같은_직책이_둘이면_422다() {
        when(personaRepository.findAllById(List.of(11L, 14L, 12L))).thenReturn(List.of(
                persona(11L, Role.TECH), persona(14L, Role.TECH), persona(12L, Role.HR)));

        assertThatThrownBy(() -> service.createInterview("Bearer t",
                new CreateInterviewRequest(null, null, List.of(11L, 14L, 12L))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void 같은_면접관을_두_번_지정하면_422다() {
        assertThatThrownBy(() -> service.createInterview("Bearer t",
                new CreateInterviewRequest(null, null, List.of(11L, 11L, 12L))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        verify(personaRepository, never()).findAllById(anyIterable());
    }

    @Test
    void 없는_면접관을_지정하면_404다() {
        when(personaRepository.findAllById(List.of(11L, 12L, 99L))).thenReturn(List.of(
                persona(11L, Role.TECH), persona(12L, Role.HR)));

        assertThatThrownBy(() -> service.createInterview("Bearer t",
                new CreateInterviewRequest(null, null, List.of(11L, 12L, 99L))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
