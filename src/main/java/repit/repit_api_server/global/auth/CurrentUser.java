package repit.repit_api_server.global.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import repit.repit_api_server.global.client.AuthServerClient;
import repit.repit_api_server.global.exception.BusinessException;
import repit.repit_api_server.global.response.UserResponse;

/**
 * 요청을 보낸 사용자를 인증 서버에 물어 확인한다.
 *
 * <p>토큰만 있고 사용자를 확인하지 않으면 소유권을 견줄 대상이 없다. 확인에 실패하면 요청을
 * 거기서 끝낸다 — 사용자를 모른 채로 남의 자료를 내려주는 편보다 낫다.
 */
@Component
@RequiredArgsConstructor
public class CurrentUser {

    private final AuthServerClient authServerClient;

    public UserResponse require(String authorization) {
        UserResponse user = authServerClient.getUser(authorization);
        if (user == null || user.getId() == null) {
            throw BusinessException.unauthorized("사용자 정보를 확인할 수 없습니다. 다시 로그인해주세요.");
        }
        return user;
    }
}
