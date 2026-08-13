package repit.repit_api_server.global.client;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;
import repit.repit_api_server.domain.metadata.dto.request.MetaDataRequest;
import repit.repit_api_server.domain.metadata.dto.response.MetaDataResponse;
import repit.repit_api_server.global.common.ApiResponse;
import repit.repit_api_server.global.response.UserResponse;

public interface AuthServerApi {

    @PostExchange("/api/v1/auth/createMetaData")
    void createMetaData(@RequestHeader("Authorization") String authorization,
                        @RequestBody MetaDataRequest request);

    @GetExchange("/api/v1/auth/getMetaData")
    ApiResponse<MetaDataResponse> getMetaData(@RequestHeader("Authorization") String authorization);

    @GetExchange("/api/v1/users/me")
    ApiResponse<UserResponse> getUser(@RequestHeader("Authorization") String authorization);
}
