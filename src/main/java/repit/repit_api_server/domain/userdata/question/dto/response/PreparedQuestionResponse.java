package repit.repit_api_server.domain.userdata.question.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 브라우저에 내려주는 준비 질문. 채점 기준인 기대 답변이 빠져 있는 것이 {@link TailoredQuestionResponse}와의 차이다.
 *
 * <p>기대 답변은 API에서 채팅 서버로만 넘어가고, 면접 중에 한 문항씩 쓰인다. 준비 조회 응답에
 * 실어 보내면 아직 출제되지 않은 문항의 채점 기준까지 미리 내려가 지원자가 거기 맞춰 답을
 * 준비할 수 있다. 질문 순서와 담당 면접관은 그대로 유지한다 — 웹이 면접관 전환을 그리는 근거다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PreparedQuestionResponse {
    private Integer id;
    private Long personaId;
    private String category;
    private String question;
    private List<String> basedOn;

    public static PreparedQuestionResponse from(TailoredQuestionResponse question) {
        return PreparedQuestionResponse.builder()
                .id(question.getId())
                .personaId(question.getPersonaId())
                .category(question.getCategory())
                .question(question.getQuestion())
                .basedOn(question.getBasedOn())
                .build();
    }

    public static List<PreparedQuestionResponse> from(List<TailoredQuestionResponse> questions) {
        if (questions == null) {
            return List.of();
        }
        return questions.stream().map(PreparedQuestionResponse::from).toList();
    }
}
