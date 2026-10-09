package repit.repit_api_server.domain.userdata.question.dto.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/** 분석 서버가 질문 사이클을 만들고 보내는 콜백. 실패면 result 없이 error가 온다. */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionCycleCallbackRequest {
    @JsonAlias("job_id")
    private String jobId;
    // "succeeded" 또는 "failed"
    private String status;
    private Result result;
    private Error error;

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Result {
        private String mode;
        private List<Question> questions;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Question {
        @JsonAlias("set_no")
        private Integer setNo;
        // tech_choice / implementation / troubleshooting / integration / structure
        private String category;
        private String question;
        // 이 질문으로 확인하려는 것. 채점 기준이다.
        private String intention;
        // 모범답안. 꼬리질문 생성과 참고용이다.
        @JsonAlias("expected_answer")
        private String expectedAnswer;
        @JsonAlias("based_on")
        private List<String> basedOn;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Error {
        @JsonAlias("status_code")
        private Integer statusCode;
        private String message;
    }
}
