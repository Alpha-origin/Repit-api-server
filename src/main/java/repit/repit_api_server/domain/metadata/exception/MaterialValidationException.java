package repit.repit_api_server.domain.metadata.exception;

import lombok.Getter;
import repit.repit_api_server.domain.metadata.dto.response.MaterialErrorResponse.MaterialError;

import java.util.List;

/** 직무·자료 저장 요청이 규칙에 걸렸다. 422와 사유 목록으로 나간다. */
@Getter
public class MaterialValidationException extends RuntimeException {

    private final List<MaterialError> errors;

    public MaterialValidationException(List<MaterialError> errors) {
        super("자료 검증 실패: " + errors);
        this.errors = List.copyOf(errors);
    }
}
