package repit.repit_api_server.domain.metadata.dto.response;

import java.util.List;

/**
 * {@code PUT /api/v1/metaData/materials} 422 응답(계약 C-1). 걸린 규칙을 모아 한 번에 돌려준다.
 *
 * <p>field는 요청 위치(documents[1], gitUrls[0], supplements 등)이고, Web이 행·영역마다 문구를 띄우는 데 쓴다.
 */
public record MaterialErrorResponse(String message, List<MaterialError> errors) {

    public static final String MESSAGE = "자료를 저장하지 못했습니다.";

    public record MaterialError(String field, Reason reason, String detail) {
    }

    /** Web 문구 표(비개발 06 §3.4)와 같은 사유 목록. */
    public enum Reason {
        JOB_ROLE_REQUIRED,
        JOB_TITLE_REQUIRED,
        JOB_TITLE_INVALID,
        JOB_TITLE_NOT_ALLOWED,
        MATERIAL_REQUIRED,
        COVER_LETTER_INVALID,
        COVER_LETTER_NOT_ALLOWED,
        NO_DOCUMENT,
        REQUIRED_DOC_MISSING,
        TOO_MANY_DOCUMENTS,
        DOC_TYPE_NOT_ALLOWED,
        NOT_PDF,
        BROKEN_PDF,
        ENCRYPTED_PDF,
        FILE_TOO_LARGE,
        DUPLICATE_FILE,
        TOO_MANY_PAGES,
        MATERIALS_RESET_REQUIRED,
        EXISTING_NOT_FOUND,
        GITHUB_REQUIRED,
        GITHUB_NOT_ALLOWED,
        INVALID_GITHUB_URL,
        SUPPLEMENT_NOT_ALLOWED,
        SUPPLEMENT_INVALID
    }
}
