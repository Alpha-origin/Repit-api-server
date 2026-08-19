package repit.repit_api_server.domain.userdata.feedback.dto.request;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import repit.repit_api_server.domain.userdata.feedback.dto.response.FrequentWordResponse;

import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class FeedbackCallbackRequest {
    private String jobId;
    private String sessionId;
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
        private Overall overall;
        private List<Item> feedbacks;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Overall {
        private Integer totalScore;
        private Integer intentAlignmentScore;
        private Integer reliabilityScore;
        private String summary;
        private List<String> strengths;
        private List<String> improvements;
        private List<FrequentWordResponse> frequentWords;
        private Integer answeredCount;
        private Integer questionCount;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        private String questionId;
        private String questionContent;
        private String intention;
        private String userAnswer;
        private String modelAnswer;
        private List<String> strengths;
        private List<String> improvements;
        private String comment;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Error {
        private Integer statusCode;
        private String message;
    }
}
