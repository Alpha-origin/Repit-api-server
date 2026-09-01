package repit.repit_api_server.domain.userdata.question.dto.response;

import org.junit.jupiter.api.Test;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.question.entity.QuestionTailorEntity;
import repit.repit_api_server.domain.userdata.question.entity.enums.TailorStatus;
import repit.repit_api_server.domain.userdata.question.preparation.FailureStage;
import repit.repit_api_server.domain.userdata.question.preparation.PreparationStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 준비 상태 조회 응답.
 *
 * <p>클라이언트가 두 축(status, chatDelivered)을 조합해 판단하던 것을 preparationStatus 하나로
 * 옮겼다. 조합이 남아 있으면 같은 상황을 두고 웹과 서버가 다른 결론을 낸다.
 */
class QuestionTailorResponseTest {

    private TailoredQuestionResponse question(int id) {
        return TailoredQuestionResponse.builder()
                .id(id).category("tech_choice").question("질문 " + id)
                .expectedAnswer("기대 답변 " + id).build();
    }

    private QuestionTailorEntity.QuestionTailorEntityBuilder tailor(InterviewMode mode) {
        return QuestionTailorEntity.builder()
                .tailorId(1L)
                .interviewId(3L)
                .userId(7L)
                .mode(mode)
                .sourceQuestions(List.of(question(1), question(2)));
    }

    @Test
    void 채팅_서버까지_넘어가야_준비_완료다() {
        QuestionTailorResponse response = QuestionTailorResponse.of(tailor(InterviewMode.SOLO)
                .status(TailorStatus.SUCCEEDED)
                .questions(List.of(question(1)))
                .chatDelivered(true)
                .build());

        assertThat(response.getPreparationStatus()).isEqualTo(PreparationStatus.READY);
        assertThat(response.getFailureStage()).isNull();
        assertThat(response.isRetryable()).isFalse();
    }

    /**
     * 재작성은 성공했는데 채팅 서버에 넘기지 못한 상태. 사유가 chat 쪽에만 남아 있어,
     * 그것을 싣지 않으면 응답만 보고는 왜 열리지 않는지 알 수 없다.
     */
    @Test
    void 전달에_실패하면_전달_단계와_사유를_함께_내려준다() {
        QuestionTailorResponse response = QuestionTailorResponse.of(tailor(InterviewMode.SOLO)
                .status(TailorStatus.SUCCEEDED)
                .questions(List.of(question(1)))
                .chatDelivered(false)
                .chatErrorMessage("채팅 서버에 오류가 발생했습니다.")
                .build());

        assertThat(response.getPreparationStatus()).isEqualTo(PreparationStatus.FAILED);
        assertThat(response.getFailureStage()).isEqualTo(FailureStage.CHAT_DELIVERY);
        assertThat(response.isRetryable()).isTrue();
        assertThat(response.getErrorMessage()).isEqualTo("채팅 서버에 오류가 발생했습니다.");
    }

    /**
     * N:1이 실패로 닫히면 최종 질문이 비고 그 자리를 기술 면접관 몫 원질문이 채운다.
     * 그대로 내려주면 웹은 질문이 있다고 보고 기술 질문 두 개짜리 면접을 N:1인 척 연다.
     */
    @Test
    void 실패한_N대1은_남은_기술_질문을_내려주지_않는다() {
        QuestionTailorResponse response = QuestionTailorResponse.of(tailor(InterviewMode.MULTI)
                .status(TailorStatus.FAILED)
                .questions(null)
                .chatDelivered(false)
                .errorMessage("질문을 준비하지 못했습니다.")
                .build());

        assertThat(response.getPreparationStatus()).isEqualTo(PreparationStatus.FAILED);
        assertThat(response.getFailureStage()).isEqualTo(FailureStage.QUESTION_GENERATION);
        assertThat(response.getQuestions()).isEmpty();
        assertThat(response.getOriginalQuestions()).isEmpty();
        assertThat(response.getQuestionCount()).isZero();
    }

    /** 면접이 열리기 전에 질문이 통째로 보이면 지원자가 미리 답을 맞춰둘 수 있다. */
    @Test
    void 준비_중인_N대1도_질문을_내려주지_않는다() {
        QuestionTailorResponse response = QuestionTailorResponse.of(tailor(InterviewMode.MULTI)
                .status(TailorStatus.PENDING)
                .chatDelivered(false)
                .build());

        assertThat(response.getPreparationStatus()).isEqualTo(PreparationStatus.PREPARING);
        assertThat(response.getQuestions()).isEmpty();
    }

    @Test
    void 열린_N대1은_확정된_질문을_내려준다() {
        QuestionTailorResponse response = QuestionTailorResponse.of(tailor(InterviewMode.MULTI)
                .status(TailorStatus.SUCCEEDED)
                .questions(List.of(question(1), question(2), question(3)))
                .chatDelivered(true)
                .build());

        assertThat(response.getPreparationStatus()).isEqualTo(PreparationStatus.READY);
        assertThat(response.getQuestionCount()).isEqualTo(3);
    }

    @Test
    void 시작하지_않은_면접은_아직_요청되지_않은_상태다() {
        QuestionTailorResponse response =
                QuestionTailorResponse.notRequested(3L, List.of(question(1)));

        assertThat(response.getPreparationStatus()).isEqualTo(PreparationStatus.NOT_REQUESTED);
        // 되살릴 작업 자체가 없다. 면접 시작을 먼저 요청해야 한다.
        assertThat(response.isRetryable()).isFalse();
    }

    /**
     * 기대 답변은 채점의 유일한 기준이라 API에서 채팅 서버로만 간다.
     * 준비 조회로 미리 내려가면 지원자가 아직 출제되지 않은 문항의 답을 맞춰둘 수 있다.
     */
    @Test
    void 준비_조회에는_기대_답변을_싣지_않는다() {
        QuestionTailorResponse response = QuestionTailorResponse.of(tailor(InterviewMode.SOLO)
                .status(TailorStatus.SUCCEEDED)
                .questions(List.of(question(1)))
                .chatDelivered(true)
                .build());

        // 질문 본문과 담당 면접관은 그대로다. 웹이 면접관 전환을 그리는 근거다.
        assertThat(response.getQuestions()).extracting(PreparedQuestionResponse::getQuestion)
                .containsExactly("질문 1");
        assertThat(response.getQuestions().getFirst())
                .extracting(PreparedQuestionResponse::getId).isEqualTo(1);
        // 기대 답변은 이 응답 타입에 자리 자체가 없다.
        assertThat(PreparedQuestionResponse.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getName)
                .doesNotContain("expectedAnswer");
    }
}
