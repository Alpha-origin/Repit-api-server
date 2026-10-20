package repit.repit_api_server.domain.userdata.interview.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import repit.repit_api_server.domain.company.dto.CompanyBadge;
import repit.repit_api_server.domain.company.dto.CompanySnapshot;
import repit.repit_api_server.domain.company.service.CompanySnapshotFactory;
import repit.repit_api_server.domain.userdata.answer.repository.AnswerRepository;
import repit.repit_api_server.domain.userdata.interview.dto.request.CreateInterviewRequest;
import repit.repit_api_server.domain.userdata.interview.dto.response.InterviewResponse;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewPersonaRepository;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.persona.entity.PersonaEntity;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Gender;
import repit.repit_api_server.domain.userdata.persona.entity.enums.InterviewTone;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Major;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Role;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Type;
import repit.repit_api_server.domain.userdata.persona.repository.PersonaRepository;
import repit.repit_api_server.domain.userdata.question.repository.QuestionRepository;
import repit.repit_api_server.domain.userdata.question.service.QuestionTailorService;
import repit.repit_api_server.global.client.ChatServerClient;
import repit.repit_api_server.global.exception.BusinessException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 면접이 고르는 것은 페르소나 하나다. id 우선, 이름 폴백이 지켜지는지 확인한다. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InterviewServiceCreateTest {

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
    @Mock
    private CompanySnapshotFactory companySnapshotFactory;

    /** 인증을 마친 요청의 주인. 확인은 시큐리티 필터가 끝냈고, 서비스는 id만 받는다. */
    private static final Long USER_ID = 7L;

    private InterviewService service;

    @BeforeEach
    void setUp() {
        service = new InterviewService(interviewRepository, questionRepository, chatServerClient,
                answerRepository, personaRepository, questionTailorService,
                interviewPersonaRepository, null, companySnapshotFactory, org.mockito.Mockito.mock(InterviewJobSnapshotService.class));


        when(personaRepository.findById(1L)).thenReturn(Optional.of(persona()));
        when(personaRepository.findByPersonaName("압박 면접관")).thenReturn(Optional.of(persona()));
        when(interviewRepository.save(any(InterviewEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(companySnapshotFactory.create(3L)).thenReturn(SNAPSHOT);
        when(companySnapshotFactory.create(4L)).thenThrow(BusinessException.notFound("선택한 기업을 찾을 수 없습니다."));
    }

    private static final CompanySnapshot SNAPSHOT = new CompanySnapshot("3", "샘플커머스", "CONSUMER",
            List.of(new CompanySnapshot.Fact("23", "TALENT", "오너십", "문제를 스스로 정의하고 결과까지 책임진다",
                    List.of("끝까지 해결한 경험을 설명함"), List.of())),
            "샘플커머스는 가상의 기업이다.");

    private PersonaEntity persona() {
        return PersonaEntity.builder()
                .personaId(1L)
                .personaName("압박 면접관")
                .major(Major.BACKEND)
                .type(Type.METICULOUS)
                .tone(InterviewTone.PRESSURING)
                .career(10)
                .gender(Gender.MALE)
                .build();
    }

    @Test
    void personaId가_있으면_이름은_보지_않는다() {
        InterviewResponse response = service.createInterview(USER_ID,
                new CreateInterviewRequest(1L, "무시되는 이름", null, null));

        assertThat(response.getPersonaId()).isEqualTo(1L);
        assertThat(response.getUserId()).isEqualTo(7L);
        verify(personaRepository, never()).findByPersonaName(any());
    }

    @Test
    void personaId가_없으면_이름으로_찾는다() {
        InterviewResponse response = service.createInterview(USER_ID,
                new CreateInterviewRequest(null, "압박 면접관", null, null));

        assertThat(response.getPersonaId()).isEqualTo(1L);
        verify(personaRepository).findByPersonaName("압박 면접관");
    }

    @Test
    void 페르소나를_아예_지정하지_않으면_422다() {
        assertThatThrownBy(() -> service.createInterview(USER_ID,
                new CreateInterviewRequest(null, "  ", null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void 없는_페르소나를_지정하면_404다() {
        when(personaRepository.findByPersonaName("없는 면접관")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createInterview(USER_ID,
                new CreateInterviewRequest(null, "없는 면접관", null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 기업을_고르면_일대일_면접에_스냅샷을_남긴다() {
        InterviewResponse response = service.createInterview(USER_ID,
                new CreateInterviewRequest(1L, null, null, 3L));

        ArgumentCaptor<InterviewEntity> saved = ArgumentCaptor.forClass(InterviewEntity.class);
        verify(interviewRepository).save(saved.capture());
        assertThat(saved.getValue().getCompanyId()).isEqualTo(3L);
        assertThat(saved.getValue().getCompanySnapshot()).isEqualTo(SNAPSHOT);
        assertThat(response.getCompany()).isEqualTo(new CompanyBadge(3L, "샘플커머스"));
    }

    @Test
    void 기업을_고르면_N대1_면접에도_스냅샷을_남긴다() {
        when(personaRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(
                PersonaEntity.builder().personaId(1L).role(Role.TECH).build(),
                PersonaEntity.builder().personaId(2L).role(Role.HR).build()));
        when(interviewRepository.save(any(InterviewEntity.class))).thenAnswer(invocation -> {
            InterviewEntity interview = invocation.getArgument(0);
            interview.setInterviewId(30L);
            return interview;
        });

        InterviewResponse response = service.createInterview(USER_ID,
                new CreateInterviewRequest(null, null, List.of(1L, 2L), 3L));

        ArgumentCaptor<InterviewEntity> saved = ArgumentCaptor.forClass(InterviewEntity.class);
        verify(interviewRepository).save(saved.capture());
        assertThat(saved.getValue().getCompanyId()).isEqualTo(3L);
        assertThat(saved.getValue().getCompanySnapshot()).isEqualTo(SNAPSHOT);
        assertThat(response.getCompany()).isEqualTo(new CompanyBadge(3L, "샘플커머스"));
    }

    @Test
    void 기업을_고르지_않으면_기업도_스냅샷도_비어_있다() {
        InterviewResponse response = service.createInterview(USER_ID,
                new CreateInterviewRequest(1L, null, null, null));

        ArgumentCaptor<InterviewEntity> saved = ArgumentCaptor.forClass(InterviewEntity.class);
        verify(interviewRepository).save(saved.capture());
        assertThat(saved.getValue().getCompanyId()).isNull();
        assertThat(saved.getValue().getCompanySnapshot()).isNull();
        assertThat(response.getCompany()).isNull();
        verify(companySnapshotFactory, never()).create(any());
    }

    /** 숨긴 기업과 없는 기업은 팩토리가 같은 404로 막는다. 면접은 저장하지 않는다. */
    @Test
    void 공개되지_않은_기업을_고르면_404이고_면접을_만들지_않는다() {
        assertThatThrownBy(() -> service.createInterview(USER_ID, new CreateInterviewRequest(1L, null, null, 4L)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);

        verify(interviewRepository, never()).save(any());
    }
}
