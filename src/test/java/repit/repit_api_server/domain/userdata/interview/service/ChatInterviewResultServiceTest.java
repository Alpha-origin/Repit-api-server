package repit.repit_api_server.domain.userdata.interview.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import repit.repit_api_server.domain.userdata.feedback.service.FeedbackDispatchService;
import repit.repit_api_server.domain.userdata.interview.dto.request.SaveInterviewRequest;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.global.exception.BusinessException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 채팅 서버가 면접을 마치고 기록을 넘기는 자리. 저장에 이어 채점 준비까지 여기서 시작한다.
 *
 * <p>채팅 서버는 이 요청을 보낸 직후 세션을 지우고, 응답이 실패면 면접 완료 처리까지 끊는다.
 * 그래서 저장 실패와 채점 준비 실패를 다르게 다뤄야 한다.
 */
@ExtendWith(MockitoExtension.class)
class ChatInterviewResultServiceTest {

    @Mock
    private InterviewService interviewService;
    @Mock
    private FeedbackDispatchService feedbackDispatchService;

    private ChatInterviewResultService service;
    private SaveInterviewRequest request;

    @BeforeEach
    void setUp() {
        service = new ChatInterviewResultService(interviewService, feedbackDispatchService);
        request = new SaveInterviewRequest("sess-1", 3L, 7L, Status.COMPLETED, null, List.of());
    }

    @Test
    void 기록을_저장한_뒤에_채점을_준비한다() {
        service.handleResult(request);

        // 채점은 저장된 질문과 답변을 읽어 보낸다. 순서가 뒤집히면 채점할 것이 없다.
        InOrder order = inOrder(interviewService, feedbackDispatchService);
        order.verify(interviewService).saveInterview(request);
        order.verify(feedbackDispatchService).onTranscriptSaved(3L);
    }

    @Test
    void 저장에_실패하면_채점하지_않고_그대로_실패시킨다() {
        doThrow(BusinessException.notFound("면접을 찾을 수 없습니다"))
                .when(interviewService).saveInterview(request);

        assertThatThrownBy(() -> service.handleResult(request))
                .isInstanceOf(BusinessException.class);

        verify(feedbackDispatchService, never()).onTranscriptSaved(3L);
    }

    @Test
    void 채점_준비가_실패해도_면접_완료_처리를_끊지_않는다() {
        doThrow(new IllegalStateException("db down"))
                .when(feedbackDispatchService).onTranscriptSaved(3L);

        // 기록은 이미 저장됐다. 여기서 실패를 돌려주면 채팅 서버의 완료 처리까지 끊긴다.
        assertThatCode(() -> service.handleResult(request)).doesNotThrowAnyException();
    }
}
