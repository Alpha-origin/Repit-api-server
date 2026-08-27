package repit.repit_api_server.domain.userdata.persona.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import repit.repit_api_server.domain.userdata.persona.entity.PersonaEntity;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Gender;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Level;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Role;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Type;
import repit.repit_api_server.domain.userdata.persona.repository.PersonaRepository;
import repit.repit_api_server.global.exception.BusinessException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 면접관 조회(GET /api/persona/getById, /getByName, /getAll).
 *
 * <p>없는 면접관을 물었을 때 그렇게 답해야 한다. 조회 실패가 500으로 나가면 클라이언트는
 * 잘못된 값 때문인지 서버가 고장난 것인지 구분할 수 없고, 로그에도 장애로 쌓인다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PersonaServiceLookupTest {

    @Mock
    private PersonaRepository personaRepository;

    private PersonaService service;

    @BeforeEach
    void setUp() {
        service = new PersonaService(personaRepository);
    }

    private PersonaEntity persona() {
        return PersonaEntity.builder()
                .personaId(5L)
                .personaName("김테크")
                .role(Role.TECH)
                .type(Type.NEUTRAL)
                .level(Level.NORMAL)
                .career(7)
                .gender(Gender.MALE)
                .build();
    }

    @Test
    void 없는_면접관을_id로_조회하면_404다() {
        when(personaRepository.findById(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPersonaById(5L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("페르소나를 찾을 수 없습니다")
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 없는_면접관을_이름으로_조회하면_찾던_이름을_알려준다() {
        when(personaRepository.findByPersonaName("없는면접관")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPersonaByName("없는면접관"))
                .isInstanceOf(BusinessException.class)
                // 이름은 바뀔 수 있다. 어떤 이름으로 찾다 실패했는지가 원인 추적의 시작점이다.
                .hasMessageContaining("없는면접관");
    }

    @Test
    void 있는_면접관은_그대로_돌려준다() {
        when(personaRepository.findById(5L)).thenReturn(Optional.of(persona()));
        when(personaRepository.findByPersonaName("김테크")).thenReturn(Optional.of(persona()));

        assertThat(service.getPersonaById(5L).getPersonaName()).isEqualTo("김테크");
        assertThat(service.getPersonaByName("김테크").getPersonaId()).isEqualTo(5L);
    }

    /** N:1 사전설정은 슬롯마다 다른 면접관 풀을 보여줘야 한다. 거르는 일은 서버가 맡는다. */
    @Test
    void 직책을_주면_그_직책만_내려준다() {
        when(personaRepository.findAllByRole(Role.HR)).thenReturn(List.of(persona()));

        assertThat(service.getAllPersona(Role.HR)).hasSize(1);
        verify(personaRepository, never()).findAll();
    }

    @Test
    void 직책이_없으면_전부_내려준다() {
        when(personaRepository.findAll()).thenReturn(List.of(persona()));

        assertThat(service.getAllPersona(null)).hasSize(1);
        verify(personaRepository, never()).findAllByRole(Role.HR);
    }
}
