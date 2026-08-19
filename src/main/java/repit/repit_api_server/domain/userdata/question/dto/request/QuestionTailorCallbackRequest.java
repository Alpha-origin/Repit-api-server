package repit.repit_api_server.domain.userdata.question.dto.request;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 분석 서버가 재작성을 마치고 보내는 콜백.
 * 재작성에 실패해도 원질문이 유효한 산출물이라, LLM 실패는 실패 콜백이 아니라
 * {@code result.tailored = false} 로 내려온다. 실패 콜백은 사전 정보 부재(422)와 내부 오류(500)뿐이다.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class QuestionTailorCallbackRequest {
    private String jobId;
    private String interviewId;
    // "succeeded" 또는 "failed"
    private String status;
    // 성공 콜백에만 존재
    private Result result;
    // 실패 콜백에만 존재
    private Error error;

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Result {
        // false면 questions에 요청에 실어보낸 원문이 그대로 담겨 돌아온다.
        private Boolean tailored;
        private List<Question> questions;
    }

    /** 바뀌는 것은 본문뿐이라 category/expectedAnswer/basedOn은 돌려주지 않는다. */
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Question {
        private Integer id;
        private String question;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Error {
        private Integer statusCode;
        private String message;
    }
}
