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
import repit.repit_api_server.domain.userdata.interview.entity.InterviewPersonaEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewPersonaRepository;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    private AnswerRepository answerRepository;
    @Mock
    private PersonaRepository personaRepository;
    @Mock
    private QuestionTailorService questionTailorService;
    @Mock
    private InterviewPersonaRepository interviewPersonaRepository;

    private InterviewService service;

    /** 인증을 마친 요청의 주인. 확인은 시큐리티 필터가 끝냈고, 서비스는 id만 받는다. */
    private static final Long USER_ID = 7L;

    @BeforeEach
    void setUp() {
        service = new InterviewService(interviewRepository, questionRepository, chatServerClient,
                answerRepository, personaRepository, questionTailorService,
                interviewPersonaRepository);

    }

    @Test
    void 없는_면접을_조회하면_404다() {
        when(interviewRepository.findById(3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getInterviewById(USER_ID, 3L))
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

        assertThat(service.getInterviewById(USER_ID, 3L).getInterviewId()).isEqualTo(3L);
    }

    /**
     * N:1 조회는 면접관 명단을 저장된 진행 순서 그대로 내려준다.
     *
     * <p>웹은 이 명단으로 면접관 카드를 그린다. 한 명이라도 빠지면 그 자리가 화면에서 사라지고,
     * 순서가 흐트러지면 질문 묶음과 면접관이 어긋난다. 담당 문항이 아직 없는 면접관도
     * 마찬가지다 — 질문은 면접이 끝나야 들어오지만 면접관은 면접을 만들 때 이미 정해져 있다.
     */
    @Test
    void N대1은_담당_문항이_없어도_면접관_전원을_순서대로_돌려준다() {
        when(interviewRepository.findById(3L)).thenReturn(Optional.of(InterviewEntity.builder()
                .interviewId(3L)
                .userId(7L)
                // N:1은 면접관이 여럿이라 단일 personaId가 비어 있다.
                .personaId(null)
                .mode(InterviewMode.MULTI)
                .sessionId("sess-1")
                .status(Status.IN_PROGRESS)
                .build()));
        when(interviewPersonaRepository.findAllByInterviewIdOrderByPersonaOrderAsc(3L)).thenReturn(List.of(
                InterviewPersonaEntity.builder().interviewId(3L).personaId(11L).personaOrder(0).build(),
                InterviewPersonaEntity.builder().interviewId(3L).personaId(12L).personaOrder(1).build(),
                InterviewPersonaEntity.builder().interviewId(3L).personaId(15L).personaOrder(2).build(),
                InterviewPersonaEntity.builder().interviewId(3L).personaId(13L).personaOrder(3).build()));

        // 질문은 아직 하나도 없다. 그래도 면접관은 전원 나와야 한다.
        assertThat(service.getInterviewById(USER_ID, 3L).getPersonaIds())
                .containsExactly(11L, 12L, 15L, 13L);
    }

    /** 1:1은 명단 자체가 없다. 없는 것을 읽으러 가면 조회마다 빈 질의가 한 번씩 더 나간다. */
    @Test
    void 일대일은_면접관_명단을_읽지_않는다() {
        when(interviewRepository.findById(3L)).thenReturn(Optional.of(InterviewEntity.builder()
                .interviewId(3L)
                .userId(7L)
                .personaId(5L)
                .mode(InterviewMode.SOLO)
                .sessionId("sess-1")
                .status(Status.COMPLETED)
                .build()));

        assertThat(service.getInterviewById(USER_ID, 3L).getPersonaIds()).isEmpty();
        verify(interviewPersonaRepository, never()).findAllByInterviewIdOrderByPersonaOrderAsc(any());
    }

    @Test
    void 남의_면접을_조회하면_403이다() {
        when(interviewRepository.findById(3L)).thenReturn(Optional.of(InterviewEntity.builder()
                .interviewId(3L)
                // 요청한 사용자(7L)와 다른 주인이다.
                .userId(9L)
                .mode(InterviewMode.SOLO)
                .sessionId("sess-1")
                .status(Status.COMPLETED)
                .build()));

        assertThatThrownBy(() -> service.getInterviewById(USER_ID, 3L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }
}
