package repit.repit_api_server.domain.userdata.feedback.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackItemEntity;

import java.util.List;
import java.util.Map;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedbackItemResponse {
    private String questionId;
    private Long personaId;
    private String questionContent;
    private String intention;
    private String userAnswer;
    private String modelAnswer;
    private List<String> strengths;
    private List<String> improvements;
    private String comment;
    // 이 문항의 축별 점수(0..100). 해당 없는 축은 값이 비어 있다.
    private Map<String, Integer> axisScores;

    public static FeedbackItemResponse from(FeedbackItemEntity item) {
        return FeedbackItemResponse.builder()
                .questionId(item.getQuestionId())
                .personaId(item.getPersonaId())
                .questionContent(item.getQuestionContent())
                .intention(item.getIntention())
                .userAnswer(item.getUserAnswer())
                .modelAnswer(item.getModelAnswer())
                .strengths(item.getStrengths())
                .improvements(item.getImprovements())
                .comment(item.getComment())
                .axisScores(item.getAxisScores())
                .build();
    }
}
