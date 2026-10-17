package repit.repit_api_server.domain.metadata.dto.response;

import repit.repit_api_server.domain.userdata.persona.support.UserJobMajors;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code GET /api/v1/metaData/materials} 응답(계약 C-1). 목록은 null 대신 빈 목록이다.
 *
 * <p>jobRole은 MAJOR_ 접두사를 뗀 직무 코드이고, 직무를 고르지 않았으면 null이다.
 * 문서 순서가 곧 index이고, 보완 입력의 documentIndex가 이 값을 가리킨다.
 */
public record MaterialsResponse(
        String jobRole,
        List<MaterialDocument> documents,
        List<String> gitUrls,
        List<Supplement> supplements) {

    /** 옛 업로드 경로(dataUpload)로 올린 PDF는 이 종류의 문서 한 건으로 보여 준다. */
    static final String LEGACY_DOCUMENT_TYPE = "PORTFOLIO";

    public record MaterialDocument(int index, String type, String fileUrl, String fileName, Integer pageCount) {
    }

    public record Supplement(String title, Integer documentIndex,
                             String goal, String role, String decision, String outcome) {
    }

    public static MaterialsResponse empty() {
        return new MaterialsResponse(null, List.of(), List.of(), List.of());
    }

    public static MaterialsResponse from(AuthMaterialsResponse auth) {
        if (auth == null) {
            return empty();
        }
        return new MaterialsResponse(
                UserJobMajors.normalize(auth.major()),
                documentsOf(auth),
                auth.gitUrls() == null ? List.of() : auth.gitUrls(),
                auth.supplements() == null ? List.of() : auth.supplements().stream()
                        .map(s -> new Supplement(s.title(), s.documentIndex(), s.goal(), s.role(), s.decision(), s.outcome()))
                        .toList());
    }

    private static List<MaterialDocument> documentsOf(AuthMaterialsResponse auth) {
        List<AuthMaterialsResponse.DocumentEntry> saved = auth.documents() == null ? List.of() : auth.documents();
        if (saved.isEmpty()) {
            // 옛 사용자는 파일명·쪽수를 저장한 적이 없어 null로 둔다.
            return auth.legacyPortfolio() == null || auth.legacyPortfolio().isBlank()
                    ? List.of()
                    : List.of(new MaterialDocument(0, LEGACY_DOCUMENT_TYPE, auth.legacyPortfolio(), null, null));
        }
        List<MaterialDocument> documents = new ArrayList<>(saved.size());
        for (int i = 0; i < saved.size(); i++) {
            AuthMaterialsResponse.DocumentEntry d = saved.get(i);
            documents.add(new MaterialDocument(i, d.type(), d.fileUrl(), d.fileName(), d.pageCount()));
        }
        return List.copyOf(documents);
    }
}
