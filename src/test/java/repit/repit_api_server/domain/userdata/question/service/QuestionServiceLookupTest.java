package repit.repit_api_server.domain.userdata.question.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.question.entity.QuestionEntity;
import repit.repit_api_server.domain.userdata.question.entity.enums.Type;
import repit.repit_api_server.domain.userdata.question.repository.QuestionRepository;
import repit.repit_api_server.global.client.AiServerClient;
import repit.repit_api_server.global.exception.BusinessException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 면접 질문 목록 조회(GET /api/question/getAll).
 *
 * <p>없는 면접을 물었을 때 그렇게 답해야 한다. 조회 실패가 500으로 나가면 클라이언트는
 * 잘못된 id 때문인지 서버가 고장난 것인지 구분할 수 없고, 로그에도 장애로 쌓인다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuestionServiceLookupTest {

    @Mock
    private QuestionRepository questionRepository;
    @Mock
    private AiServerClient aiServerClient;
    @Mock
    private InterviewRepository interviewRepository;

    private QuestionService service;

    @BeforeEach
    void setUp() {
        service = new QuestionService(questionRepository, aiServerClient, interviewRepository);
    }

    @Test
    void 없는_면접의_질문을_조회하면_404다() {
        when(interviewRepository.findById(3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAllByInterview(3L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("면접을 찾을 수 없습니다")
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);

        verify(questionRepository, never()).findAllByInterviewId(3L);
    }

    @Test
    void 있는_면접의_질문을_돌려준다() {
        when(interviewRepository.findById(3L)).thenReturn(Optional.of(InterviewEntity.builder()
                .interviewId(3L)
                .userId(7L)
                .mode(InterviewMode.SOLO)
                .sessionId("sess-1")
                .status(Status.COMPLETED)
                .build()));
        when(questionRepository.findAllByInterviewId(3L)).thenReturn(List.of(QuestionEntity.builder()
                .questionId(901L)
                .interviewId(3L)
                .type(Type.ORIGINAL)
                .intention("도입 근거 확인")
                .content("WebFlux 를 도입한 이유가 무엇인가요?")
                .createdAt(LocalDateTime.parse("2026-08-18T01:00:00"))
                .build()));

        assertThat(service.getAllByInterview(3L)).hasSize(1);
    }
}
