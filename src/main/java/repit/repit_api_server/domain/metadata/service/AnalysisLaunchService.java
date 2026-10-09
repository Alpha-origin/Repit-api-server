package repit.repit_api_server.domain.metadata.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import repit.repit_api_server.domain.metadata.dto.request.GenerateRequest;
import repit.repit_api_server.domain.metadata.dto.request.ProfileRequest;
import repit.repit_api_server.domain.metadata.dto.response.GenerateResponse;
import repit.repit_api_server.domain.metadata.dto.response.MetaDataResponse;
import repit.repit_api_server.domain.metadata.entity.enums.AnalysisResultType;
import repit.repit_api_server.domain.metadata.repository.AnalysisDataRepository;
import repit.repit_api_server.domain.userdata.question.service.QuestionPoolService;
import repit.repit_api_server.global.auth.AuthUser;
import repit.repit_api_server.global.client.AiServerClient;
import repit.repit_api_server.global.exception.ExternalApiException;

import java.time.LocalDateTime;
import java.util.function.Supplier;

/**
 * 포트폴리오 분석을 시작하고, 그 실행을 접수한다.
 *
 * <p>분석은 두 곳에서 시작된다 — 자료를 올리는 길과 분석만 다시 요청하는 길이다. 접수를 한쪽에만
 * 붙여두면 다른 길로 시작한 분석은 주인 없이 남는다. 주인 없는 분석으로는 면접이 열리지 않는다.
 * 면접 시작이 사용자의 최근 완료 분석을 집어 드는 것으로 시작하기 때문이다. 그래서 요청과 접수를
 * 한 묶음으로 두고 두 길이 같은 것을 쓰게 한다.
 *
 * <p>소유자는 시큐리티 필터가 이미 확인한 사용자다. 예전에는 여기서 인증 서버에 한 번 더 물었고,
 * 그 조회가 실패하면 주인 없는 분석이 남았다. 이제 인증되지 않은 요청은 여기까지 오지 않는다.
 */
@Service
@RequiredArgsConstructor
public class AnalysisLaunchService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisLaunchService.class);

    private static final String CALLBACK_PATH = "/api/v1/ai/callback";
    // 옛 /generate 결과와 섞이지 않게 경로를 나눈다.
    private static final String PROFILE_CALLBACK_PATH = "/api/v1/ai/profile/callback";

    private final AiServerClient aiServerClient;
    private final AiMetaDataService aiMetaDataService;
    private final MetaService metaService;
    private final AnalysisDataRepository analysisDataRepository;
    private final QuestionPoolService questionPoolService;

    @Value("${app.callback-base-url}")
    private String callbackBaseUrl;

    /**
     * 종합 데이터 분석(/profile)을 시작한다. 질문은 이 결과가 도착한 뒤 사이클로 따로 만든다.
     *
     * @param major 사용자 전공. 분석 서버가 탐색 우선순위에만 쓰고, 없으면 비워 보낸다.
     */
    public GenerateResponse launch(Long userId, String major, MetaDataResponse metaData) {
        ProfileRequest request = ProfileRequest.builder()
                .major(major == null || major.isBlank() ? null : major)
                .portfolioUrl(metaData.getFileUrl())
                .githubUrls(metaData.getGitUrls())
                .callbackUrl(callbackBaseUrl + PROFILE_CALLBACK_PATH)
                .build();
        return launch(userId, AnalysisResultType.PROFILE, () -> aiServerClient.requestProfile(request));
    }

    /**
     * 쓸 수 있는 종합 데이터가 없는 사용자면 저장된 자료로 요청한다. 면접 준비는 이 분석과 질문 사이클을 기다렸다가
     * 이어간다. 두 경우다.
     * <ul>
     *   <li>한 번도 요청한 적이 없다 — 예전 /generate 분석만 있는 사용자를 옮기는 길이다.</li>
     *   <li>가장 나중에 요청한 것이 끝났는데, 분석 서버가 그것으로는 더 이상 질문 사이클을 만들 수 없다고 했다(422).
     *       분석 서버가 종합 데이터 형태를 바꾸면 기존 사용자가 모두 여기로 온다.</li>
     * </ul>
     *
     * <p>가장 나중에 요청한 것만 본다. 다시 요청한 분석이 진행 중이거나 실패했으면 또 요청하지 않는다 — 면접을
     * 시작할 때마다 무거운 분석이 돈다. 실패했으면 면접 준비가 분석부터 하라고 알린다.
     *
     * <p>올린 자료가 모자라면 요청하지 않는다. 분석 서버가 접수부터 거부하고, 면접 준비가 분석부터 하라고 알린다.
     *
     * <p>면접 시작이 겹치면 둘 다 "요청해야 함"을 보고 분석을 두 번 요청한다. 사용자 단위로 잠그고 다시 확인해,
     * 뒤에 온 요청은 앞 요청이 접수를 마칠 때까지 기다렸다가 그 분석을 기다린다.
     */
    // ponytail: 잠근 채 인증·분석 서버를 부르므로 그동안 DB 커넥션을 붙잡는다. 사용자당 드물게 한 번 도는 길이라 감수한다.
    @Transactional
    public void launchIfMissing(AuthUser authUser) {
        if (!needsProfile(authUser.id())) {
            return;
        }
        analysisDataRepository.lockProfileLaunch(authUser.id());
        if (!needsProfile(authUser.id())) {
            return;
        }
        MetaDataResponse saved = metaService.getMetaData(authUser.token());
        if (saved == null || saved.getFileUrl() == null || saved.getFileUrl().isBlank()
                || saved.getGitUrls() == null || saved.getGitUrls().isEmpty()) {
            return;
        }
        log.info("쓸 수 있는 종합 데이터가 없어 저장된 자료로 분석을 요청합니다. userId={}", authUser.id());
        launch(authUser.id(), authUser.user().getMajor(), saved);
    }

    private boolean needsProfile(Long userId) {
        return analysisDataRepository.findTopByUserIdAndResultTypeOrderByCreatedAtDesc(userId, AnalysisResultType.PROFILE)
                .map(latest -> latest.getResult() != null && questionPoolService.needsReanalysis(latest.getJobId()))
                .orElse(true);
    }

    // 옛 /generate-mock. 분석 서버가 /generate를 걷어낼 때 함께 지운다.
    public GenerateResponse launchMock(Long userId, MetaDataResponse metaData) {
        GenerateRequest request = GenerateRequest.builder()
                .portfolio_url(metaData.getFileUrl())
                .github_urls(metaData.getGitUrls())
                .callback_url(callbackBaseUrl + CALLBACK_PATH)
                .build();
        return launch(userId, AnalysisResultType.LEGACY_GENERATE, () -> aiServerClient.generateMock(request));
    }

    private GenerateResponse launch(Long userId, AnalysisResultType resultType, Supplier<GenerateResponse> call) {
        // 분석 서버에 넘기기 직전 시각. 이 작업에 남아 있는 결과가 지난 실행의 것인지 가르는 기준이다.
        LocalDateTime requestedAt = LocalDateTime.now();
        GenerateResponse response = call.get();
        registerJob(response, userId, requestedAt, resultType);
        return response;
    }

    /**
     * 이번 분석 실행을 접수한다. 소유자를 기록하고, 같은 jobId에 남아 있던 지난 결과를 걷어낸다.
     *
     * <p>걷어내지 않으면 구독이 붙는 순간 분석 서버의 콜백보다 먼저 옛 결과가 완료 이벤트로 나간다.
     *
     * <p>다만 이 시점에는 분석 서버가 이미 작업을 접수한 뒤다. 기록이 실패했다고 요청 전체를
     * 실패시키면 클라이언트가 jobId를 받지 못해 결과를 영영 조회할 수 없게 되므로, 기록 실패는
     * 예외로 번지지 않게 막는다.
     */
    private void registerJob(GenerateResponse response, Long userId, LocalDateTime requestedAt,
                             AnalysisResultType resultType) {
        if (response == null || response.getJobId() == null) {
            // jobId가 없으면 구독도 조회도 할 수 없다. 성공으로 돌려주면 원인을 찾을 수 없다.
            log.error("분석 서버 응답에 jobId가 없습니다. status={}, message={}",
                    response == null ? null : response.getStatus(),
                    response == null ? null : response.getMessage());
            throw new ExternalApiException("분석 서버가 작업 번호를 돌려주지 않았습니다.", null, null);
        }

        try {
            aiMetaDataService.registerJob(response.getJobId(), userId, requestedAt, resultType);
        } catch (RuntimeException e) {
            log.error("분석 작업을 접수하지 못했습니다. jobId={}", response.getJobId(), e);
        }
    }
}
