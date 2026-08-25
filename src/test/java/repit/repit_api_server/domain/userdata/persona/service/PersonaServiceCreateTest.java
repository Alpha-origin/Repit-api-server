package repit.repit_api_server.domain.userdata.persona.service;

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
import repit.repit_api_server.domain.userdata.persona.dto.request.PersonaRequest;
import repit.repit_api_server.domain.userdata.persona.entity.PersonaEntity;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Gender;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Level;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Major;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Role;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Type;
import repit.repit_api_server.domain.userdata.persona.repository.PersonaRepository;
import repit.repit_api_server.global.exception.BusinessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 직책과 난이도는 저장되고 나면 되돌릴 근거가 없다. 요청에 있는 값이 그대로 남는지 확인한다. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PersonaServiceCreateTest {

    @Mock
    private PersonaRepository personaRepository;

    @Captor
    private ArgumentCaptor<PersonaEntity> saved;

    private PersonaService service;

    @BeforeEach
    void setUp() {
        service = new PersonaService(personaRepository);
        when(personaRepository.save(any(PersonaEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private PersonaRequest request(Role role, Major major) {
        PersonaRequest request = new PersonaRequest();
        request.setPersonaName("면접관");
        request.setRole(role);
        request.setMajor(major);
        request.setLevel(Level.HARD);
        request.setType(Type.FRIENDLY);
        request.setCareer(5);
        request.setGender(Gender.FEMALE);
        return request;
    }

    @Test
    void 요청한_직책이_그대로_저장된다() {
        service.createPersona(request(Role.HR, null));

        verify(personaRepository).save(saved.capture());
        assertThat(saved.getValue().getRole()).isEqualTo(Role.HR);
        // 전공은 기술 면접관에게만 있는 값이다.
        assertThat(saved.getValue().getMajor()).isNull();
    }

    @Test
    void 직책을_안_보내면_422다() {
        assertThatThrownBy(() -> service.createPersona(request(null, Major.BACKEND)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        // 조용히 TECH로 저장되면 인사·CEO 면접관이 기술 면접관으로 묻힌다.
        verify(personaRepository, never()).save(any());
    }

    @Test
    void 기술_면접관은_전공이_없으면_422다() {
        assertThatThrownBy(() -> service.createPersona(request(Role.TECH, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void 기술_면접관은_전공을_유지한다() {
        service.createPersona(request(Role.TECH, Major.BACKEND));

        verify(personaRepository).save(saved.capture());
        assertThat(saved.getValue().getRole()).isEqualTo(Role.TECH);
        assertThat(saved.getValue().getMajor()).isEqualTo(Major.BACKEND);
    }

    @Test
    void 요청한_난이도가_그대로_저장된다() {
        service.createPersona(request(Role.CEO, null));

        verify(personaRepository).save(saved.capture());
        assertThat(saved.getValue().getLevel()).isEqualTo(Level.HARD);
    }

    @Test
    void 난이도를_안_보내면_422다() {
        PersonaRequest request = request(Role.CEO, null);
        request.setLevel(null);

        assertThatThrownBy(() -> service.createPersona(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        verify(personaRepository, never()).save(any());
    }
}
