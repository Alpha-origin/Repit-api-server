package repit.repit_api_server.global.client;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;
import repit.repit_api_server.domain.metadata.dto.request.MetaDataRequest;
import repit.repit_api_server.global.common.ApiResponse;
import repit.repit_api_server.domain.metadata.dto.response.MetaDataResponse;
import repit.repit_api_server.global.response.UserResponse;

@Component
@RequiredArgsConstructor
public class AuthServerClient {

    private final AuthServerApi authServerApi;

    // Auth 서버에 metaData 전달
    public void createMetaData(String authorization, MetaDataRequest request) {
        try {
            authServerApi.createMetaData(authorization, request);
        } catch (HttpClientErrorException e) {
            throw new RuntimeException("인증 실패");
        }
    }

    // Auth 서버에서 metaData 반환
    public MetaDataResponse getMetaData(String authorization) throws HttpClientErrorException {
        try {
            ApiResponse<MetaDataResponse> response = authServerApi.getMetaData(authorization);

            if (response == null) {
                return null;
            }
            return response.getData();
        }
        catch (HttpClientErrorException.Unauthorized e) {
            throw new ResponseStatusException(e.getStatusCode(), "인증 실패");
        }

    }

    public UserResponse getUser(String authorization) {
        try {
            ApiResponse<UserResponse> response = authServerApi.getUser(authorization);

            if (response == null) {
                return null;
            }

            return response.getData();

        } catch (HttpClientErrorException.Unauthorized e) {
            throw new RuntimeException("인증 실패");
        }
    }
}
