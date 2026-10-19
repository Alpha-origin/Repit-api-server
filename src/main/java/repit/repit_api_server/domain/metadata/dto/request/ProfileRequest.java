package repit.repit_api_server.domain.metadata.dto.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/** 분석 서버 POST /profile 요청(계약 C-3·G-C3). 저장된 자료 전체를 분석한다. */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProfileRequest {
    private String jobRole;
    private List<Document> documents;
    private List<String> githubUrls;
    private List<Supplement> supplements;
    private String callbackUrl;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String jobTitle;
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<CoverLetter> coverLetters;

    @Getter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Document {
        private String type;
        private String url;
        private String title;
    }

    @Getter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Supplement {
        private String title;
        private Integer documentIndex;
        private String goal;
        private String role;
        private String decision;
        private String outcome;
    }

    @Getter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CoverLetter {
        private String question;
        private String answer;
    }
}
