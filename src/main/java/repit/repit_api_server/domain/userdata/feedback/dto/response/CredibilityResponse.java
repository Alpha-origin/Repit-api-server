package repit.repit_api_server.domain.userdata.feedback.dto.response;

import java.util.List;

/**
 * 신뢰성 — 답변 사이의 모순(competency-v1). 표시만 하고 종합 점수에 들어가지 않는다.
 *
 * <p>꼬리질문에서 설명하지 못한 것은 모순이 아니다. 앞 답변과 뒤집힐 때만 여기에 실린다.
 *
 * @param score  일관성 점수(0..100). 답변이 1개면 비어 있다
 * @param issues 서로 어긋난 답변 쌍. 없으면 빈 목록
 */
public record CredibilityResponse(Integer score, List<Issue> issues) {

    public CredibilityResponse {
        issues = issues == null ? List.of() : issues;
    }

    /**
     * @param kind        ROLE / TECH_CHOICE / CAUSE / DETAIL / OTHER
     * @param questionIds 서로 어긋난 두 문항. 분석 서버에 보낸 질문 id 그대로라 feedbacks[].questionId와 맞춰 잇는다
     * @param quotes      각 문항 답변의 원문 인용. questionIds와 같은 순서다
     */
    public record Issue(String kind, List<String> questionIds, List<String> quotes) {
    }
}
