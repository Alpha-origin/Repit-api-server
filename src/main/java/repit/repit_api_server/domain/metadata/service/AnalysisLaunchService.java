package repit.repit_api_server.domain.metadata.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import repit.repit_api_server.domain.metadata.dto.request.GenerateRequest;
import repit.repit_api_server.domain.metadata.dto.response.GenerateResponse;
import repit.repit_api_server.domain.metadata.dto.response.MetaDataResponse;
import repit.repit_api_server.global.client.AiServerClient;
import repit.repit_api_server.global.client.AuthServerClient;
import repit.repit_api_server.global.exception.ExternalApiException;
import repit.repit_api_server.global.response.UserResponse;

import java.time.LocalDateTime;
import java.util.function.Function;

/**
 * 포트폴리오 분석을 시작하고, 그 실행을 접수한다.
 *
 * <p>분석은 두 곳에서 시작된다 — 자료를 올리는 길과 분석만 다시 요청하는 길이다. 접수를 한쪽에만
 * 붙여두면 다른 길로 시작한 분석은 주인 없이 남는다. 주인 없는 분석으로는 면접이 열리지 않는다.
 * 면접 시작이 사용자의 최근 완료 분석을 집어 드는 것으로 시작하기 때문이다. 그래서 요청과 접수를
 * 한 묶음으로 두고 두 길이 같은 것을 쓰게 한다.
 */
@Service
@RequiredArgsConstructor
public class AnalysisLaunchService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisLaunchService.class);

    private static final String CALLBACK_PATH = "/api/v1/ai/callback";

    private final AiServerClient aiServerClient;
    private final AuthServerClient authServerClient;
    private final AiMetaDataService aiMetaDataService;

    @Value("${app.callback-base-url}")
    private String callbackBaseUrl;

    public GenerateResponse launch(String authorization, MetaDataResponse metaData) {
        return launch(authorization, metaData, aiServerClient::generate);
    }

    public GenerateResponse launchMock(String authorization, MetaDataResponse metaData) {
        return launch(authorization, metaData, aiServerClient::generateMock);
    }

    private GenerateResponse launch(String authorization, MetaDataResponse metaData,
                                    Function<GenerateRequest, GenerateResponse> call) {
        GenerateRequest request = GenerateRequest.builder()
                .portfolio_url(metaData.getFileUrl())
                .github_urls(metaData.getGitUrls())
                .callback_url(callbackBaseUrl + CALLBACK_PATH)
                .build();

        // 소유자는 분석 서버에 요청하기 전에 확인해둔다. 접수는 분석 서버가 jobId를 돌려줘야
        // 할 수 있는데, 그 응답이 콜백보다 늦게 오는 일이 있다. 그때 사용자 조회까지 뒤에 두면
        // 소유자가 더 늦게 붙고, 그 사이 도착한 콜백이 만든 행은 주인 없이 남는다.
        Long userId = resolveOwner(authorization);

        // 분석 서버에 넘기기 직전 시각. 이 작업에 남아 있는 결과가 지난 실행의 것인지 가르는 기준이다.
        LocalDateTime requestedAt = LocalDateTime.now();
        GenerateResponse response = call.apply(request);
        registerJob(response, userId, requestedAt);
        return response;
    }

    /**
     * 이 분석을 누구 것으로 남길지 확인한다.
     *
     * <p>확인하지 못해도 요청 자체는 진행한다. 여기서 막으면 분석을 아예 시작하지 못한다.
     */
    private Long resolveOwner(String authorization) {
        try {
            UserResponse user = authServerClient.getUser(authorization);
            if (user != null && user.getId() != null) {
                return user.getId();
            }
            log.error("분석 작업의 소유자를 확인하지 못했습니다. 사용자 정보가 비어 있습니다.");
        } catch (RuntimeException e) {
            log.error("분석 작업의 소유자를 확인하지 못했습니다.", e);
        }
        return null;
    }

    /**
     * 이번 분석 실행을 접수한다. 소유자를 기록하고, 같은 jobId에 남아 있던 지난 결과를 걷어낸다.
     *
     * <p>걷어내지 않으면 구독이 붙는 순간 분석 서버의 콜백보다 먼저 옛 결과가 완료 이벤트로 나간다.
     * 그래서 소유자를 확인하지 못했더라도 접수 자체는 한다.
     *
     * <p>다만 이 시점에는 분석 서버가 이미 작업을 접수한 뒤다. 기록이 실패했다고 요청 전체를
     * 실패시키면 클라이언트가 jobId를 받지 못해 결과를 영영 조회할 수 없게 되므로, 기록 실패는
     * 예외로 번지지 않게 막는다.
     */
    private void registerJob(GenerateResponse response, Long userId, LocalDateTime requestedAt) {
        if (response == null || response.getJobId() == null) {
            // jobId가 없으면 구독도 조회도 할 수 없다. 성공으로 돌려주면 원인을 찾을 수 없다.
            log.error("분석 서버 응답에 jobId가 없습니다. status={}, message={}",
                    response == null ? null : response.getStatus(),
                    response == null ? null : response.getMessage());
            throw new ExternalApiException("분석 서버가 작업 번호를 돌려주지 않았습니다.", null, null);
        }

        if (userId == null) {
            log.error("소유자 없이 분석 작업을 접수합니다. 이 결과로는 면접을 열 수 없습니다. jobId={}",
                    response.getJobId());
        }

        try {
            aiMetaDataService.registerJob(response.getJobId(), userId, requestedAt);
        } catch (RuntimeException e) {
            log.error("분석 작업을 접수하지 못했습니다. jobId={}", response.getJobId(), e);
        }
    }
}
