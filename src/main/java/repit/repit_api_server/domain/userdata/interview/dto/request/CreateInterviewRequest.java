package repit.repit_api_server.domain.userdata.interview.dto.request;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 면접 생성 요청.
 *
 * <p>면접이 고르는 것은 페르소나 하나뿐이라 그것만 받는다. 예전에는 페르소나 등록용 DTO를 그대로 써서
 * 전공·타입·경력·성별까지 받는 것처럼 보였지만, 실제로는 이름만 쓰고 나머지는 버렸다.
 *
 * <p>{@code personaId}가 있으면 그것을 쓰고, 없으면 {@code personaName}으로 찾는다. 이름은 바뀔 수 있는
 * 값이라 id 쪽이 안전하지만, 웹이 이름으로 보내던 기존 방식도 그대로 받는다.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CreateInterviewRequest {
    private Long personaId;
    private String personaName;
}
