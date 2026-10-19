package repit.repit_api_server.domain.metadata.dto.request;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ProfileRequestSerializationTest {
    @Test void IT는_일반_필드와_옛_필드를_보내지_않는다() {
        var request = ProfileRequest.builder().jobRole("QA_ENGINEER")
                .documents(List.of(new ProfileRequest.Document("TEST_PLAN", "https://s3/test.pdf", "테스트 계획.pdf")))
                .githubUrls(List.of()).supplements(List.of()).coverLetters(List.of()).callbackUrl("https://api/callback").build();
        var json = new ObjectMapper().valueToTree(request);
        assertThat(json.has("jobTitle")).isFalse();
        assertThat(json.has("coverLetters")).isFalse();
        assertThat(json.has("major")).isFalse();
        assertThat(json.has("portfolioUrl")).isFalse();
        assertThat(json.get("documents").get(0).get("title").asText()).isEqualTo("테스트 계획.pdf");
    }

    @Test void 일반은_자소서와_직무명을_보낸다() {
        var request = ProfileRequest.builder().jobRole("GEN_MARKETING").jobTitle("마케터")
                .documents(List.of()).githubUrls(List.of()).supplements(List.of())
                .coverLetters(List.of(new ProfileRequest.CoverLetter("역량", "경험"))).build();
        var json = new ObjectMapper().valueToTree(request);
        assertThat(json.get("jobTitle").asText()).isEqualTo("마케터");
        assertThat(json.get("coverLetters").get(0).get("answer").asText()).isEqualTo("경험");
    }
}
