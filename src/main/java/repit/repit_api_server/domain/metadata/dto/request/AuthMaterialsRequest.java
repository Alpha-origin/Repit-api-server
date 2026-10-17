package repit.repit_api_server.domain.metadata.dto.request;

import repit.repit_api_server.domain.metadata.dto.response.AuthMaterialsResponse.DocumentEntry;
import repit.repit_api_server.domain.metadata.dto.response.AuthMaterialsResponse.SupplementEntry;

import java.util.List;

/**
 * 인증 서버 {@code PUT /api/v1/auth/materials} 요청(계약 C-2). 넷을 인증 서버가 한 트랜잭션으로 바꾼다.
 *
 * @param major MAJOR_ 접두사를 붙인 저장값(MAJOR_QA_ENGINEER)
 */
public record AuthMaterialsRequest(
        String major,
        List<DocumentEntry> documents,
        List<String> gitUrls,
        List<SupplementEntry> supplements) {
}
