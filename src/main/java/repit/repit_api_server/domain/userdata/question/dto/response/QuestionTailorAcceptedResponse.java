package repit.repit_api_server.domain.userdata.question.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 분석 서버가 재작성 요청을 접수하고 돌려주는 202 응답. 결과는 콜백으로 온다. */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class QuestionTailorAcceptedResponse {
    private String jobId;
    private String interviewId;
    private String status;
    private String message;
}
