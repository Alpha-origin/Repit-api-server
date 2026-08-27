package repit.repit_api_server.domain.userdata.interview.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 면접에 입장할 수 있게 됐음을 알리는 SSE 페이로드.
 *
 * <p>웹이 채팅 서버에 붙는 데 필요한 것만 담는다. 질문 본문은 넣지 않는다 — 면접 중에는
 * 채팅 서버가 한 문항씩 내려주고, 채점 기준인 의도까지 미리 내려가면 지원자가 거기 맞춰
 * 답을 준비할 수 있다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InterviewReadyResponse {
    private Long interviewId;
    private String sessionId;
    // 재작성본으로 여는지, 원질문 폴백으로 여는지. 어느 쪽이든 면접은 열린다.
    private Boolean tailored;
    private String message;

    // 재작성이 실패해도 원질문으로 면접은 열린다. 못 여는 것은 채팅 서버 전달이 실패했을 때뿐이다.
    private String errorMessage;

    public static InterviewReadyResponse ready(Long interviewId, String sessionId, Boolean tailored) {
        return InterviewReadyResponse.builder()
                .interviewId(interviewId)
                .sessionId(sessionId)
                .tailored(tailored)
                .message("면접 준비가 끝났습니다.")
                .build();
    }

    public static InterviewReadyResponse failed(Long interviewId, String errorMessage) {
        return InterviewReadyResponse.builder()
                .interviewId(interviewId)
                .message("질문은 준비됐지만 채팅 서버에 전달하지 못했습니다. 잠시 후 다시 시도해주세요.")
                .errorMessage(errorMessage)
                .build();
    }
}
