package repit.repit_api_server.domain.userdata.feedback.dto.response;

import java.util.List;

/**
 * 피드백의 "○○ 기준으로 보면" 섹션(00 §6-2). 점수와 무관하다 — 종합 점수와 산출 근거 계산에 들어가지 않는다.
 *
 * <p>kind·focus는 서버가 면접 스냅샷의 fact에서 채운다(모델 값은 쓰지 않는다). focus는 fact의 title이다.
 * questionIds는 분석 서버에 보낸 질문 id 그대로라, 웹은 feedbacks[].questionId와 맞춰 문항을 잇는다. 콜백을 저장할 때 답변 있는
 * 문항이 하나도 남지 않는 항목은 뺀다({@code CompanyFitReader}). 그 전에 저장된 결과에는 빈 목록인 항목이 남아 있을 수 있다.
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
