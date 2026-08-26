package repit.repit_api_server.domain.userdata.interview.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import repit.repit_api_server.domain.userdata.answer.repository.AnswerRepository;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewPersonaRepository;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.persona.repository.PersonaRepository;
import repit.repit_api_server.domain.userdata.question.repository.QuestionRepository;
import repit.repit_api_server.domain.userdata.question.service.QuestionTailorService;
import repit.repit_api_server.global.client.AuthServerClient;
import repit.repit_api_server.global.client.ChatServerClient;
import repit.repit_api_server.global.exception.BusinessException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 면접 단건 조회(GET /api/interviews/get).
 *
 * <p>없는 면접을 물었을 때 그렇게 답해야 한다. 조회 실패가 500으로 나가면 클라이언트는
 * 잘못된 id 때문인지 서버가 고장난 것인지 구분할 수 없고, 로그에도 장애로 쌓인다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InterviewServiceLookupTest {

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

    private InterviewService service;

    @BeforeEach
    void setUp() {
        service = new InterviewService(interviewRepository, questionRepository, chatServerClient,
                authServerClient, answerRepository, personaRepository, questionTailorService,
                interviewPersonaRepository);
    }

    @Test
    void 없는_면접을_조회하면_404다() {
        when(interviewRepository.findById(3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getInterviewById(3L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("면접을 찾을 수 없습니다")
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 있는_면접은_그대로_돌려준다() {
        when(interviewRepository.findById(3L)).thenReturn(Optional.of(InterviewEntity.builder()
                .interviewId(3L)
                .userId(7L)
                .personaId(5L)
                .mode(InterviewMode.SOLO)
                .sessionId("sess-1")
                .status(Status.COMPLETED)
                .build()));

        assertThat(service.getInterviewById(3L).getInterviewId()).isEqualTo(3L);
    }
}
