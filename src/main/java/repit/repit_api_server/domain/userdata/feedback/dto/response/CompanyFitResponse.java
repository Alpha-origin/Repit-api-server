package repit.repit_api_server.domain.userdata.feedback.dto.response;

import java.util.List;

/**
 * 피드백의 "○○ 기준으로 보면" 섹션(00 §6-2). 점수와 무관하다 — 종합 점수와 산출 근거 계산에 들어가지 않는다.
 *
 * <p>kind·focus는 분석 서버가 요청의 fact에서 복사해 채운다. focus는 fact의 title이다.
 * questionIds는 분석 서버에 보낸 질문 id 그대로라, 웹은 feedbacks[].questionId와 맞춰 문항을 잇는다.
 */
public record CompanyFitResponse(String comment, List<Item> matchedFocus, List<Gap> gaps) {

    public CompanyFitResponse {
        matchedFocus = matchedFocus == null ? List.of() : matchedFocus;
        gaps = gaps == null ? List.of() : gaps;
    }

    public record Item(String factId, String kind, String focus, String evidence, List<String> questionIds) {
        public Item {
            questionIds = questionIds == null ? List.of() : questionIds;
        }
    }

    public record Gap(String factId, String kind, String focus, String suggestion, List<String> questionIds) {
        public Gap {
            questionIds = questionIds == null ? List.of() : questionIds;
        }
    }
}
