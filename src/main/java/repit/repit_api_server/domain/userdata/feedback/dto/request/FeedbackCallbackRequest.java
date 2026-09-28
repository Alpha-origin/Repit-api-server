package repit.repit_api_server.domain.userdata.feedback.dto.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import repit.repit_api_server.domain.userdata.feedback.dto.response.FrequentWordResponse;
import tools.jackson.databind.JsonNode;

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
        // N:1 면접에만 실린다. 1:1 콜백에는 없다.
        private List<Persona> personas;
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
        // 종합 점수의 산출 근거. axis-v1 이후 콜백에만 실린다. 없으면 이전 방식으로 매긴 점수다.
        // 계약이 확정되지 않아 모양을 가리지 않고 받는다. 푸는 것은 ScoreBreakdownReader가 한다.
        private JsonNode scoreBreakdown;
    }

    /** 면접관별 종합. 문항이 2~3개뿐이라 점수는 하나만 온다. */
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Persona {
        private Long personaId;
        // 분석 서버는 이 값을 role로 보낸다. 우리 저장 이름이 persona_role이라 필드명은 그대로 두고
        // 받는 이름만 맞춘다. 이름이 어긋나면 직책이 통째로 비어 면접관 구분이 사라진다.
        @JsonAlias({"role", "personaRole"})
        private String personaRole;
        // 담당 답변이 없는 면접관은 0이 아니라 비어서 온다. "0점"과 "평가 대상 없음"은 다르다.
        private Integer score;
        private String comment;
        private List<String> strengths;
        private List<String> improvements;
        private Integer answeredCount;
        private Integer questionCount;
        // 이 면접관 점수의 산출 근거. 1:1 종합과 같은 구조다.
        private JsonNode scoreBreakdown;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        private String questionId;
        // N:1에서 이 질문을 던진 면접관. 1:1 콜백에는 없다.
        private Long personaId;
        private String questionContent;
        private String intention;
        private String userAnswer;
        private String modelAnswer;
        private List<String> strengths;
        private List<String> improvements;
        private String comment;
        // 이 문항의 축별 점수(0..100). 해당 없는 축은 비어 있다. 예: {"INTENT": 88, "ACCURACY": null}
        // 종합처럼 [{axis, score}]로 올 수도 있어 모양을 가리지 않고 받는다.
        private JsonNode axisScores;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Error {
        private Integer statusCode;
        private String message;
    }
}
