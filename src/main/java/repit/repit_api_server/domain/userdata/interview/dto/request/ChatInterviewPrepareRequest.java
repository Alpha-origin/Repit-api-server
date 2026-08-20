package repit.repit_api_server.domain.userdata.interview.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Gender;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Major;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Type;

import java.util.List;

/**
 * 채팅 서버에 면접을 여는 요청.
 * 질문 재작성이 끝난 뒤 이 서버가 DB에 모아둔 값을 통째로 넘긴다.
 * 채팅 서버가 분석 결과를 따로 되가져가지 않도록 질문 본문까지 여기에 다 실린다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatInterviewPrepareRequest {
    private String sessionId;
    private Long interviewId;
    private Long userId;
    private Status status;
    // 원질문을 만든 /generate 작업. 채팅 서버가 분석 결과를 참조할 때 쓴다.
    private String jobId;
    private Persona persona;
    // 아래 questions가 재작성본인지 원질문 폴백인지.
    private boolean tailored;
    private List<Question> questions;

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Persona {
        private Long personaId;
        private String personaName;
        private Major major;
        private Type type;
        private int career;
        private Gender gender;
    }

    /** 면접 질문 한 건. 재작성 여부와 무관하게 원질문을 함께 실어 채팅 서버가 대조할 수 있게 한다. */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Question {
        private Integer id;
        private String category;
        // 면접에서 실제로 물을 본문. 재작성본이거나, 폴백이면 원질문과 같다.
        private String question;
        private String originalQuestion;
        // 재작성 후에도 유지해야 할 검증 포인트.
        private String expectedAnswer;
        private List<String> basedOn;
    }
}
