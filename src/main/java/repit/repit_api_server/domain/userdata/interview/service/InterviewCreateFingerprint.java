package repit.repit_api_server.domain.userdata.interview.service;

import repit.repit_api_server.domain.userdata.interview.dto.request.CreateInterviewRequest;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * 면접 생성 설정의 지문. 같은 Idempotency-Key로 온 요청이 처음과 같은 설정인지 가린다.
 *
 * <p>생성이 실제로 쓰는 값만, 생성과 같은 규칙으로 정규화해 담는다 — personaIds가 있으면 N:1이고 나머지 면접관 값은 보지 않는다.
 * 1:1은 personaId가 있으면 그것을, 없으면 personaName을 쓴다. N:1 면접관 순서는 진행 순서를 정하므로 그대로 둔다.
 * 서버가 만드는 값(스냅샷·시각·새 id)은 넣지 않는다. 키 이름 순으로 직렬화해 필드 순서가 결과를 바꾸지 않는다.
 */
final class InterviewCreateFingerprint {

    private InterviewCreateFingerprint() {
    }

    static String of(CreateInterviewRequest request) {
        boolean multi = request.getPersonaIds() != null && !request.getPersonaIds().isEmpty();
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("mode", multi ? InterviewMode.MULTI.name() : InterviewMode.SOLO.name());
        if (multi) {
            canonical.put("personaIds", request.getPersonaIds());
        } else if (request.getPersonaId() != null) {
            canonical.put("personaId", request.getPersonaId());
        } else {
            canonical.put("personaName", request.getPersonaName());
        }
        canonical.put("companyId", request.getCompanyId());
        return sha256(JsonMapper.shared().writeValueAsString(canonical));
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
