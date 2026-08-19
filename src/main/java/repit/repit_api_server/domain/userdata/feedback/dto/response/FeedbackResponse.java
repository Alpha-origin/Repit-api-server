package repit.repit_api_server.domain.userdata.feedback.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackEntity;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackItemEntity;
import repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackStatus;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedbackResponse {
    private Long feedbackId;
    private Long interviewId;
    private String sessionId;
    private FeedbackStatus status;

    private Integer totalScore;
    private Integer intentAlignmentScore;
    private Integer reliabilityScore;
    private String summary;
    private List<String> strengths;
    private List<String> improvements;
    private List<FrequentWordResponse> frequentWords;
    private Integer answeredCount;
    private Integer questionCount;

    // 실패한 경우에만 채워진다.
    private String errorMessage;

    private List<FeedbackItemResponse> feedbacks;
    private LocalDateTime createdAt;

    public static FeedbackResponse of(FeedbackEntity feedback, List<FeedbackItemEntity> items) {
        return FeedbackResponse.builder()
                .feedbackId(feedback.getFeedbackId())
                .interviewId(feedback.getInterviewId())
                .sessionId(feedback.getSessionId())
                .status(feedback.getStatus())
                .totalScore(feedback.getTotalScore())
                .intentAlignmentScore(feedback.getIntentAlignmentScore())
                .reliabilityScore(feedback.getReliabilityScore())
                .summary(feedback.getSummary())
                .strengths(feedback.getStrengths())
                .improvements(feedback.getImprovements())
                .frequentWords(feedback.getFrequentWords())
                .answeredCount(feedback.getAnsweredCount())
                .questionCount(feedback.getQuestionCount())
                .errorMessage(feedback.getErrorMessage())
                .feedbacks(items.stream().map(FeedbackItemResponse::from).toList())
                .createdAt(feedback.getCreatedAt())
                .build();
    }
}
