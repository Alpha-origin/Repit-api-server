package repit.repit_api_server.domain.userdata.interview.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import repit.repit_api_server.domain.userdata.question.entity.enums.Type;

import java.time.LocalDateTime;

/** 면접 전체 기록에 실려 오는 질문 한 건. 필드는 채팅 서버가 내려주는 형태에 맞춘다. */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ChatQuestionResponse {
    private Long questionId;
    private Long parentId;
    private Type questionType;
    private String questionIntention;
    private String questionContent;
    // 이 질문을 던진 면접관. 면접을 열 때 우리가 붙여 보낸 값이 그대로 돌아온다.
    private Long personaId;
    private LocalDateTime questionCreatedAt;
}
