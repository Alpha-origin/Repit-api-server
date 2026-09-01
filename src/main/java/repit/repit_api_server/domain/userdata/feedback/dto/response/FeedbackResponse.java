package repit.repit_api_server.domain.userdata.feedback.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackEntity;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackItemEntity;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackPersonaEntity;
import repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackStatus;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.persona.entity.PersonaEntity;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Level;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Type;

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

    /**
     * 면접관의 성향(스타일)과 난이도. 같은 점수라도 어떤 면접에서 받은 점수인지에 따라 읽는 법이 달라서
     * 채점 결과와 함께 보낸다.
     *
     * <p>N:1도 면접관 셋이 같은 성향·난이도로 묶이므로 면접마다 값 하나로 족하다. 면접이나
     * 면접관이 남아 있지 않으면 비어 있다.
     */
    private Type style;
    private Level level;

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

    /**
     * @param interview 이 피드백이 나온 면접. 면접이 남아 있지 않으면 null이다.
     * @param persona   이 면접의 면접관. 성향·난이도는 채점 결과가 아니라 여기서 읽는다.
     *                  N:1은 면접관이 여럿이지만 성향·난이도가 모두 같아 그중 하나로 대표한다.
     *                  면접관이 남아 있지 않으면 null이다.
     */
    public static FeedbackResponse of(FeedbackEntity feedback,
                                      InterviewEntity interview,
                                      PersonaEntity persona,
                                      List<FeedbackPersonaEntity> personas,
                                      List<FeedbackItemEntity> items) {
        return FeedbackResponse.builder()
                .feedbackId(feedback.getFeedbackId())
                .interviewId(feedback.getInterviewId())
                .sessionId(feedback.getSessionId())
                .status(feedback.getStatus())
                .mode(interview == null ? null : interview.getMode())
                .style(persona == null ? null : persona.getType())
                .level(persona == null ? null : persona.getLevel())
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
