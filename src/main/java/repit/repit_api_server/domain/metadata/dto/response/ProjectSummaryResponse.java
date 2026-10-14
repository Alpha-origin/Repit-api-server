package repit.repit_api_server.domain.metadata.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * 종합 데이터(/profile) 결과의 projectSummary.
 *
 * <p>N:1 질문 구성은 이 값을 근거로 비개발 면접관의 질문을 새로 만든다. 분석 서버는 저장소가 없어
 * 여기 없는 값은 어디서도 구하지 못하므로, 넘기기 전에 이 형태로 한 번 읽어 확인한다.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProjectSummaryResponse {
    private String overview;
    private List<Repository> repositories;
    private List<CoreFeature> coreFeatures;
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
            private List<String> basedOn;
    }
}
