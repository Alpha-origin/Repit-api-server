package repit.repit_api_server.global.response;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class UserResponse {
    private Long id;
    private String email;
    private String nickname;
    private String username;
    private String major;
    private String provider;
    private String role;
    private LocalDateTime createAt;
}
