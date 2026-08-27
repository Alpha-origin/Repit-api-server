package repit.repit_api_server.domain.userdata.answer.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import repit.repit_api_server.domain.userdata.answer.dto.request.AnswerRequest;
import repit.repit_api_server.domain.userdata.answer.entity.AnswerEntity;
import repit.repit_api_server.domain.userdata.answer.repository.AnswerRepository;
import repit.repit_api_server.domain.userdata.question.entity.QuestionEntity;
import repit.repit_api_server.domain.userdata.question.entity.enums.Type;
import repit.repit_api_server.domain.userdata.question.repository.QuestionRepository;
import repit.repit_api_server.global.client.AuthServerClient;
import repit.repit_api_server.global.exception.BusinessException;
import repit.repit_api_server.global.response.UserResponse;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 답변 저장과 단건 조회.
 *
 * <p>없는 것을 가리켰을 때 그렇게 답해야 한다. 성공 응답에 빈 본문을 실어 보내면 클라이언트는
 * 답변이 남은 줄 알고 넘어가고, 저장되지 않은 답변은 그대로 사라진다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AnswerServiceTest {

    @Mock
    private QuestionRepository questionRepository;
    @Mock
    private AnswerRepository answerRepository;
    @Mock
    private AuthServerClient authServerClient;

    private AnswerService service;

    @BeforeEach
    void setUp() {
        service = new AnswerService(questionRepository, answerRepository, authServerClient);

        UserResponse user = mock(UserResponse.class);
        when(user.getId()).thenReturn(7L);
        when(authServerClient.getUser("Bearer token")).thenReturn(user);
        when(answerRepository.save(any(AnswerEntity.class))).thenAnswer(call -> call.getArgument(0));
    }

    private AnswerRequest request() {
        return AnswerRequest.builder()
                .interviewId(3L)
                .questionId(901L)
                .responseTime(90)
                .content("스레드가 I/O 대기에 묶였습니다.")
                .build();
    }

    private QuestionEntity question() {
        return QuestionEntity.builder()
                .questionId(901L)
                .interviewId(3L)
                .type(Type.ORIGINAL)
                .intention("도입 근거 확인")
                .content("WebFlux 를 도입한 이유가 무엇인가요?")
                .createdAt(LocalDateTime.parse("2026-08-18T01:00:00"))
                .build();
    }

    /** 채팅 서버가 넘긴 답변은 응답 시간이 비어 있을 수 있다. */
    private AnswerEntity answer(Integer responseTime) {
        return AnswerEntity.builder()
                .answerId(501L)
                .interviewId(3L)
                .questionId(901L)
                .userId(7L)
                .responseTime(responseTime)
                .content("스레드가 I/O 대기에 묶였습니다.")
                .createdAt(LocalDateTime.parse("2026-08-18T01:01:30"))
                .build();
    }

    @Test
    void 없는_질문에_답변하면_404고_저장하지_않는다() {
        when(questionRepository.findById(901L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createAnswer("Bearer token", request()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("질문을 찾을 수 없습니다")
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // 성공으로 답하면 사용자는 답변이 남은 줄 알고 면접을 이어간다.
        verify(answerRepository, never()).save(any());
    }

    @Test
    void 있는_질문에_답변하면_저장한다() {
        when(questionRepository.findById(901L)).thenReturn(Optional.of(question()));

        service.createAnswer("Bearer token", request());

        verify(answerRepository).save(any(AnswerEntity.class));
    }

    @Test
    void 응답_시간을_안_보내면_0으로_묻지_않는다() {
        when(questionRepository.findById(901L)).thenReturn(Optional.of(question()));

        service.createAnswer("Bearer token", AnswerRequest.builder()
                .interviewId(3L)
                .questionId(901L)
                .content("스레드가 I/O 대기에 묶였습니다.")
                .build());

        // 0초 만에 답한 것과 응답 시간을 보내지 않은 것은 다르다.
        ArgumentCaptor<AnswerEntity> saved = ArgumentCaptor.forClass(AnswerEntity.class);
        verify(answerRepository).save(saved.capture());
        assertThat(saved.getValue().getResponseTime()).isNull();
    }

    @Test
    void 응답_시간이_비어_있어도_답변을_돌려준다() {
        when(answerRepository.findById(501L)).thenReturn(Optional.of(answer(null)));

        // 채팅 서버는 응답 시간을 비워 보낼 수 있다. 여기서 언박싱하다 NPE 가 나면 500 이다.
        assertThat(service.getAnswerById(501L).getResponseTime()).isNull();
    }

    @Test
    void 응답_시간이_비어_있는_답변_하나가_목록_전체를_막지_않는다() {
        when(answerRepository.findAllByInterviewId(3L))
                .thenReturn(List.of(answer(90), answer(null)));

        assertThat(service.getAllAnswer(3L)).hasSize(2);
    }

    @Test
    void 없는_답변을_조회하면_404다() {
        when(answerRepository.findById(501L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAnswerById(501L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("답변을 찾을 수 없습니다")
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 있는_답변은_그대로_돌려준다() {
        when(answerRepository.findById(501L)).thenReturn(Optional.of(answer(90)));

        assertThat(service.getAnswerById(501L).getAnswerId()).isEqualTo(501L);
    }
}
