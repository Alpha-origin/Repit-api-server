package repit.repit_api_server.domain.metadata.dto.response;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * /generate 성공 콜백의 result.project_summary.
 *
 * <p>N:1 질문 구성은 이 값을 근거로 비개발 면접관의 질문을 새로 만든다. 분석 서버는 저장소가 없어
 * 여기 없는 값은 어디서도 구하지 못하므로, 넘기기 전에 이 형태로 한 번 읽어 확인한다.
 *
 * <p>/generate 와이어 포맷은 snake_case인데 /questions/tailor/multi 는 camelCase로 받는다.
 * 어느 쪽으로 저장돼 있든 읽히도록 두 표기를 모두 받아둔다 — 한쪽만 맞춰두면 이름이 어긋난 날
 * 필드가 조용히 비고, 질문은 근거 없이 생성된다.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProjectSummaryResponse {
    private String overview;
    private List<Repository> repositories;
    @JsonAlias({"core_features", "coreFeatures"})
    private List<CoreFeature> coreFeatures;
    @JsonAlias({"tech_stack", "techStack"})
    private List<String> techStack;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Repository {
        private String repo;
        // 저장소 역할(api_server/frontend 등). 면접관 직책과는 무관하다.
        private String role;
        private String description;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CoreFeature {
        private String name;
        private String description;
        @JsonAlias({"based_on", "basedOn"})
        private List<String> basedOn;
    }
}
