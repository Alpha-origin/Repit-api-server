package repit.repit_api_server.domain.userdata.feedback.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackEntity;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackItemEntity;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackPersonaEntity;
import repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackStatus;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;

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

    // 이 피드백이 나온 면접의 방식. 1:1이면 SOLO, 면접관이 교대하는 N:1이면 MULTI다.
    // 면접 기록이 남아 있지 않은 예외적인 경우에만 비어 있다.
    private InterviewMode mode;

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

    // 면접관별 종합. N:1 면접에만 채워지고 1:1은 비어 있다.
    private List<FeedbackPersonaResponse> personas;

    private List<FeedbackItemResponse> feedbacks;
    private LocalDateTime createdAt;

    public static FeedbackResponse of(FeedbackEntity feedback,
                                      InterviewMode mode,
                                      List<FeedbackPersonaEntity> personas,
                                      List<FeedbackItemEntity> items) {
        return FeedbackResponse.builder()
                .feedbackId(feedback.getFeedbackId())
                .interviewId(feedback.getInterviewId())
                .sessionId(feedback.getSessionId())
                .status(feedback.getStatus())
                .mode(mode)
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
                .personas(personas.stream().map(FeedbackPersonaResponse::from).toList())
                .feedbacks(items.stream().map(FeedbackItemResponse::from).toList())
                .createdAt(feedback.getCreatedAt())
                .build();
    }
}
