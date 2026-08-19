package repit.repit_api_server.domain.userdata.feedback.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackItemEntity;

import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedbackItemResponse {
    private String questionId;
    private String questionContent;
    private String intention;
    private String userAnswer;
    private String modelAnswer;
    private List<String> strengths;
    private List<String> improvements;
    private String comment;

    public static FeedbackItemResponse from(FeedbackItemEntity item) {
        return FeedbackItemResponse.builder()
                .questionId(item.getQuestionId())
                .questionContent(item.getQuestionContent())
                .intention(item.getIntention())
                .userAnswer(item.getUserAnswer())
                .modelAnswer(item.getModelAnswer())
                .strengths(item.getStrengths())
                .improvements(item.getImprovements())
                .comment(item.getComment())
                .build();
    }
}
