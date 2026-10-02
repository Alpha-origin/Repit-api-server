package repit.repit_api_server.domain.metadata.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/** 분석 서버 POST /profile 요청. 자료를 한 번 분석해 질문 생성의 재료인 종합 데이터를 만든다. */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProfileRequest {
    // 사용자 전공. 탐색 우선순위에만 쓰이고 없으면 비워 보낸다.
    private String major;
    private String portfolioUrl;
    private List<String> githubUrls;
    private String callbackUrl;
}
