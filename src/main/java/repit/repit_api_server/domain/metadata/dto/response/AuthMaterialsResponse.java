package repit.repit_api_server.domain.metadata.dto.response;

import java.util.List;

/**
 * 인증 서버 {@code GET /api/v1/auth/materials} 응답(계약 C-2).
 *
 * <p>major는 MAJOR_ 접두사가 붙은 저장값이다. legacyPortfolio는 새 형식으로 저장한 적 없는
 * 옛 사용자의 PDF 주소이고, 그때 documents는 빈 목록으로 온다.
 */
public record AuthMaterialsResponse(
        String major,
        List<DocumentEntry> documents,
        List<String> gitUrls,
        List<SupplementEntry> supplements,
        String legacyPortfolio,
        String jobTitle,
        List<CoverLetterEntry> coverLetters) {

    public AuthMaterialsResponse(String major, List<DocumentEntry> documents, List<String> gitUrls, List<SupplementEntry> supplements, String legacyPortfolio) {
        this(major, documents, gitUrls, supplements, legacyPortfolio, null, List.of());
    }

    public record CoverLetterEntry(String question, String answer) {
    }

    public record DocumentEntry(String type, String fileUrl, String fileName, Integer pageCount) {
    }

    public record SupplementEntry(String title, Integer documentIndex,
                                  String goal, String role, String decision, String outcome) {
    }
}
