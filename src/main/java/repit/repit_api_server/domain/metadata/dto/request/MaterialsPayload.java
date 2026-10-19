package repit.repit_api_server.domain.metadata.dto.request;

import java.util.List;

/**
 * {@code PUT /api/v1/metaData/materials}의 {@code payload} 파트(계약 C-1).
 *
 * <p>문서 순서가 곧 index다. 저장된 문서는 {@code source: "EXISTING"} + {@code fileUrl},
 * 새로 첨부한 파일은 {@code source: "NEW"} + {@code fileKey}("file0"~"file4", 같은 요청의 파트 이름)로 온다.
 */
public record MaterialsPayload(
        String jobRole,
        List<DocumentInput> documents,
        List<String> gitUrls,
        List<SupplementInput> supplements) {

    public static final String SOURCE_NEW = "NEW";
    public static final String SOURCE_EXISTING = "EXISTING";

    public record DocumentInput(String type, String source, String fileKey, String fileUrl) {
    }

    public record SupplementInput(String title, Integer documentIndex,
                                  String goal, String role, String decision, String outcome) {
    }
}
