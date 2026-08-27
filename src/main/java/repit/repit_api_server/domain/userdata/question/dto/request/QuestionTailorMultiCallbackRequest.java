package repit.repit_api_server.domain.userdata.question.dto.request;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 분석 서버가 N:1 질문 구성을 마치고 보내는 콜백.
 *
 * <p>1:1 재작성과 달리 폴백이 없다. {@code tailored: false} 같은 플래그가 없고, 어느 단계가
 * 실패하든 {@code status: "failed"}로 온다. 신규 질문은 분석 서버 말고 만들 데가 없어
 * 실패하면 면접을 열 수 없다.
 *
 * <p>questions 배열 순서가 그대로 면접 진행 순서다. 기술 질문이 먼저 오고 otherPersonas
 * 순서대로 이어진다.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class QuestionTailorMultiCallbackRequest {
    private String jobId;
    private String interviewId;
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
        private List<Question> questions;
    }

    /**
     * 1:1 콜백은 본문만 돌려주지만 이쪽은 전체가 온다.
     * 신규 질문 4개는 요청에 없던 것이라 여기서 받은 값이 유일한 원본이다 —
     * expectedAnswer를 버리면 그 4문항의 채점 기준이 사라진다.
     */
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Question {
        private Integer id;
        // 이 질문을 맡은 면접관. 요청에 실어 보낸 personaId가 그대로 돌아온다.
        private Long personaId;
        private String category;
        private String question;
        private String expectedAnswer;
        private List<String> basedOn;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Error {
        private Integer statusCode;
        private String message;
    }
}
