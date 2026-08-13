package repit.repit_api_server.global.client;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import repit.repit_api_server.domain.metadata.dto.request.MetaDataRequest;
import repit.repit_api_server.global.common.ApiResponse;
import repit.repit_api_server.domain.metadata.dto.response.MetaDataResponse;
import repit.repit_api_server.global.response.UserResponse;

@Component
@RequiredArgsConstructor
public class AuthServerClient {

    private static final String SERVER_NAME = "인증";

    private final AuthServerApi authServerApi;
    private final ExternalApiExecutor executor;

    // Auth 서버에 metaData 전달
    public void createMetaData(String authorization, MetaDataRequest request) {
        executor.execute(SERVER_NAME,
                () -> {
                    authServerApi.createMetaData(authorization, request);
                    return null;
                },
                this::resolveMessage, false);
    }

    // Auth 서버에서 metaData 반환
    public MetaDataResponse getMetaData(String authorization) {
        ApiResponse<MetaDataResponse> response = executor.execute(SERVER_NAME,
                () -> authServerApi.getMetaData(authorization),
                this::resolveMessage, true);

        return response == null ? null : response.getData();
    }

    public UserResponse getUser(String authorization) {
        ApiResponse<UserResponse> response = executor.execute(SERVER_NAME,
                () -> authServerApi.getUser(authorization),
                this::resolveMessage, true);

        return response == null ? null : response.getData();
    }

    private String resolveMessage(HttpStatusCode status) {
        if (status.value() == 401) {
            return "인증에 실패했습니다. 다시 로그인해주세요.";
        }
        if (status.value() == 403) {
            return "접근 권한이 없습니다.";
        }
        if (status.value() == 404) {
            return "사용자 정보를 찾을 수 없습니다.";
        }
        if (status.is5xxServerError()) {
            return "인증 서버에 오류가 발생했습니다.";
        }
        return "인증 서버 요청이 올바르지 않습니다. (" + status.value() + ")";
    }
}
