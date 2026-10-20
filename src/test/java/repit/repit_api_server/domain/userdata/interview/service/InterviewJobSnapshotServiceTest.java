package repit.repit_api_server.domain.userdata.interview.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import repit.repit_api_server.domain.userdata.interview.entity.*;
import repit.repit_api_server.domain.userdata.interview.entity.enums.*;
import repit.repit_api_server.domain.userdata.interview.repository.*;
import repit.repit_api_server.domain.userdata.persona.entity.PersonaEntity;
import repit.repit_api_server.domain.userdata.persona.entity.enums.*;
import repit.repit_api_server.domain.userdata.persona.repository.PersonaRepository;
import repit.repit_api_server.domain.userdata.question.repository.QuestionTailorRepository;
import repit.repit_api_server.global.auth.AuthUser;
import repit.repit_api_server.global.response.UserResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class InterviewJobSnapshotServiceTest {
    @Mock InterviewRepository interviews;
    @Mock InterviewPersonaRepository members;
    @Mock PersonaRepository personas;
    @Mock QuestionTailorRepository tailors;
    InterviewJobSnapshotService service;

    @BeforeEach void setup() {
        service = new InterviewJobSnapshotService(interviews, members, personas, tailors, mock(jakarta.persistence.EntityManager.class));
    }

    AuthUser user(String title) {
        UserResponse user = new UserResponse();
        ReflectionTestUtils.setField(user, "id", 7L);
        ReflectionTestUtils.setField(user, "major", "MAJOR_GEN_MARKETING");
        ReflectionTestUtils.setField(user, "jobTitle", title);
        return new AuthUser(user, "token");
    }

    PersonaEntity shared() {
        return PersonaEntity.builder().personaId(5L).personaName("전문 면접관").role(Role.TECH)
                .major(Major.GEN_MARKETING).jobTitle("옛 직무명").type(Type.REALISTIC)
                .tone(InterviewTone.GENTLE).level(Level.NORMAL).gender(Gender.MALE).build();
    }

    InterviewEntity interview(long id) {
        return InterviewEntity.builder().interviewId(id).userId(7L).personaId(5L)
                .mode(InterviewMode.SOLO).status(Status.IN_PROGRESS).build();
    }

    @Test void 공유_면접관을_갱신하지_않고_면접별로_시작_값을_복제한다() {
        PersonaEntity shared = shared();
        InterviewEntity first = interview(1L), second = interview(2L);
        when(interviews.findForJobSnapshot(1L)).thenReturn(Optional.of(first));
        when(interviews.findForJobSnapshot(2L)).thenReturn(Optional.of(second));
        when(personas.findById(5L)).thenReturn(Optional.of(shared));
        AtomicLong ids = new AtomicLong(10);
        when(personas.save(any())).thenAnswer(inv -> {
            PersonaEntity next = inv.getArgument(0);
            return next.toBuilder().personaId(ids.incrementAndGet()).build();
        });
        service.capture(user("퍼포먼스 마케터"), 1L);
        service.capture(user("브랜드 마케터"), 2L);
        var saved = org.mockito.ArgumentCaptor.forClass(PersonaEntity.class);
        verify(personas, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(PersonaEntity::getJobTitle)
                .containsExactly("퍼포먼스 마케터", "브랜드 마케터");
        assertThat(shared.getJobTitle()).isEqualTo("옛 직무명");
        assertThat(first.getPersonaId()).isNotEqualTo(second.getPersonaId());
        assertThat(saved.getValue().getDisplayName()).isEqualTo("전문 면접관");
    }

    @Test void 접수_실패_뒤_다시_시작해도_이미_확정된_직무명을_바꾸지_않는다() {
        InterviewEntity interview = interview(1L); interview.setPersonaId(11L);
        PersonaEntity snapshot = shared().toBuilder().personaId(11L).interviewSnapshot(true)
                .jobTitle("시작 시점").build();
        when(interviews.findForJobSnapshot(1L)).thenReturn(Optional.of(interview));
        when(personas.findById(11L)).thenReturn(Optional.of(snapshot));
        service.capture(user("나중 직무"), 1L);
        assertThat(snapshot.getJobTitle()).isEqualTo("시작 시점");
        verify(personas, never()).save(any());
    }

    @Test void 다대일은_첫_자리_링크만_면접_전용으로_바꾼다() {
        InterviewEntity interview = interview(1L); interview.setMode(InterviewMode.MULTI); interview.setPersonaId(null);
        InterviewPersonaEntity link = InterviewPersonaEntity.builder().interviewId(1L).personaId(5L).personaOrder(0).build();
        when(interviews.findForJobSnapshot(1L)).thenReturn(Optional.of(interview));
        when(members.findAllByInterviewIdOrderByPersonaOrderAsc(1L)).thenReturn(List.of(link));
        when(personas.findById(5L)).thenReturn(Optional.of(shared()));
        when(personas.save(any())).thenAnswer(inv -> ((PersonaEntity) inv.getArgument(0)).toBuilder().personaId(11L).build());
        service.capture(user("마케터"), 1L);
        assertThat(link.getPersonaId()).isEqualTo(11L);
        verify(members).save(link);
        verify(interviews, never()).save(any());
    }

    @Test void 다른_사용자는_잠근_면접의_스냅샷을_만들_수_없다() {
        InterviewEntity interview = interview(1L); interview.setUserId(99L);
        when(interviews.findForJobSnapshot(1L)).thenReturn(Optional.of(interview));
        assertThatThrownBy(() -> service.capture(user("마케터"), 1L)).hasMessageContaining("본인의");
        verifyNoInteractions(personas, members);
    }
}
