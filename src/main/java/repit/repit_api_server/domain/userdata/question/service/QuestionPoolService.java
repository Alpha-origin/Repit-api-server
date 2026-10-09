package repit.repit_api_server.domain.userdata.question.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import repit.repit_api_server.domain.metadata.dto.response.GenerateResponse;
import repit.repit_api_server.domain.metadata.entity.AnalysisDataEntity;
import repit.repit_api_server.domain.metadata.entity.enums.AnalysisResultType;
import repit.repit_api_server.domain.metadata.entity.enums.AnalysisStatus;
import repit.repit_api_server.domain.metadata.repository.AnalysisDataRepository;
import repit.repit_api_server.domain.metadata.service.AiMetaDataService;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.question.dto.request.QuestionCycleCallbackRequest;
import repit.repit_api_server.domain.userdata.question.dto.request.QuestionCycleRequest;
import repit.repit_api_server.domain.userdata.question.dto.response.TailoredQuestionResponse;
import repit.repit_api_server.domain.userdata.question.entity.PoolQuestionEntity;
import repit.repit_api_server.domain.userdata.question.entity.QuestionCycleEntity;
import repit.repit_api_server.domain.userdata.question.entity.enums.CycleStatus;
import repit.repit_api_server.domain.userdata.question.repository.PoolQuestionRepository;
import repit.repit_api_server.domain.userdata.question.repository.QuestionCycleRepository;
import repit.repit_api_server.global.client.AiServerClient;
import repit.repit_api_server.global.exception.BusinessException;
import repit.repit_api_server.global.exception.ExternalApiException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 질문 풀. 종합 데이터로 질문 사이클을 만들어 두고, 면접을 시작할 때 세트를 하나씩 꺼내 준다.
 *
 * <p>사이클 하나는 세트 3개다. 2번째 세트를 꺼낼 때 다음 사이클(대기본)을 미리 요청하고, 3번째 세트를
 * 꺼내면 대기본으로 넘어간다. 꺼낼 세트가 없거나 자료를 새로 분석하는 중이면 면접 준비는 다음 사이클을
 * 기다린다 — 그 이어받기는 {@link QuestionTailorService}가 사이클 콜백에서 한다.
 *
 * <p>분석 서버 호출은 트랜잭션 밖에서 한다. 느린 응답을 기다리는 동안 사이클 행 잠금과 DB 커넥션을
 * 붙잡지 않으려고 DB 작업과 호출을 나눈다.
 */
@Service
@RequiredArgsConstructor
public class QuestionPoolService {

    private static final Logger log = LoggerFactory.getLogger(QuestionPoolService.class);

    private static final String CALLBACK_PATH = "/api/v1/ai/question-cycle/callback";
    private static final String STATUS_SUCCEEDED = "succeeded";

    /** 사이클 하나의 세트 수. */
    private static final int SET_COUNT = 3;
    /** 새 사이클이 겹치지 않게 할 같은 모드의 최근 사이클 수. */
    private static final int EXCLUDE_CYCLES = 2;
    /** 질문을 기다리는 면접이 있을 때 한 사이클을 요청하는 최대 횟수(처음 포함). */
    private static final int MAX_ATTEMPTS_WHILE_WAITING = 2;

    private final QuestionCycleRepository questionCycleRepository;
    private final PoolQuestionRepository poolQuestionRepository;
    private final AnalysisDataRepository analysisDataRepository;
    private final AiMetaDataService aiMetaDataService;
    private final AiServerClient aiServerClient;
    private final TransactionTemplate transactionTemplate;

    @Value("${app.callback-base-url}")
    private String callbackBaseUrl;

    // 이 시간이 지나도록 콜백이 오지 않은 생성 요청은 잃어버린 것으로 보고 다시 요청한다.
    // 분석 서버는 콜백 전송에 실패하면 결과를 버리므로, 기다리기만 하면 그 모드의 면접이 영영 열리지 않는다.
    @Value("${app.question-pool.generating-timeout:5m}")
    private Duration generatingTimeout;

    /** 세트 하나의 문항 수. 1:1은 이것이 면접 문항 수고, N:1은 기술 면접관 몫이다. */
    public static int setSize(InterviewMode mode) {
        return mode == InterviewMode.MULTI ? 2 : 5;
    }

    /**
     * 꺼낸 세트와 그 세트가 나온 종합 데이터.
     *
     * @param questions 비어 있으면(null) 꺼낼 세트가 없어 다음 사이클을 기다려야 한다.
     * @param major     종합 데이터를 만들 때 실어 보낸 전공. 사용자 정보가 없는 콜백에서 재작성 축으로 쓴다.
     */
    public record DrawnSet(String profileJobId, List<TailoredQuestionResponse> questions,
                           Object projectSummary, String major) {
        public boolean waiting() {
            return questions == null;
        }
    }

    /** 사이클 콜백을 반영한 결과. 기다리는 면접 준비를 이어갈지 실패로 닫을지 정하는 데 쓴다. */
    public record CycleOutcome(Long cycleId, Long userId, InterviewMode mode, boolean succeeded, String errorMessage) {
    }

    private record Draw(DrawnSet set, QuestionCycleEntity toRequest) {
    }

    /**
     * 면접에 쓸 세트를 꺼내 사용 표시를 남긴다. 면접을 중간에 그만둬도 되돌리지 않는다.
     *
     * <p>이미 이 면접에 꺼내 준 세트가 있으면 그것을 돌려준다. 준비를 다시 시도할 때마다 새 세트를
     * 꺼내면 한 면접에 세트가 몇 개씩 사라진다.
     *
     * <p>기다려야 하는데 그 사이클을 요청하지 못했으면 예외로 알린다. 기다려도 올 질문이 없다.
     * 대기본 요청만 실패한 것은 넘어간다 — 이번 면접에는 지장이 없고, 다음 면접을 시작할 때 다시 요청한다.
     */
    public DrawnSet takeSet(Long userId, InterviewMode mode, Long interviewId) {
        Draw draw = transactionTemplate.execute(status -> draw(userId, mode, interviewId));
        if (draw.toRequest() != null) {
            try {
                requestCycle(draw.toRequest());
            } catch (RuntimeException e) {
                if (draw.set().waiting()) {
                    throw e;
                }
            }
        }
        return draw.set();
    }

    private Draw draw(Long userId, InterviewMode mode, Long interviewId) {
        List<PoolQuestionEntity> taken = poolQuestionRepository.findAllByInterviewIdOrderByQuestionIdAsc(interviewId);
        if (!taken.isEmpty()) {
            AnalysisDataEntity source = questionCycleRepository.findById(taken.getFirst().getCycleId())
                    .flatMap(cycle -> analysisDataRepository.findById(cycle.getProfileJobId()))
                    .orElseGet(() -> latestCompleted(userId));
            return new Draw(drawn(source, taken), null);
        }

        // 자료를 새로 분석하는 중이면 이전 자료로 열지 않고 그 분석을 기다린다. 사이클은 분석 콜백이 요청한다.
        AnalysisDataEntity latest = analysisDataRepository
                .findTopByUserIdAndResultTypeOrderByCreatedAtDesc(userId, AnalysisResultType.PROFILE)
                .orElseThrow(QuestionPoolService::noProfile);
        if (aiMetaDataService.isAnalyzing(latest)) {
            return new Draw(new DrawnSet(latest.getJobId(), null, null, null), null);
        }

        AnalysisDataEntity profile = latestCompleted(userId);
        String profileJobId = profile.getJobId();
        QuestionCycleEntity active = lock(profileJobId, mode, CycleStatus.ACTIVE)
                .or(() -> promoteStandby(profileJobId, mode))
                .orElse(null);

        if (active == null) {
            // 마지막 사이클이 아직 생성 중이거나 실패했으면 그 사이클을, 다 썼거나 없으면 다음 사이클을 기다린다.
            QuestionCycleEntity last = questionCycleRepository
                    .findTopByProfileJobIdAndModeOrderByCycleNoDesc(profileJobId, mode)
                    .orElse(null);
            int awaited = last == null ? 1
                    : last.getStatus() == CycleStatus.EXHAUSTED ? last.getCycleNo() + 1
                    : last.getCycleNo();
            return new Draw(new DrawnSet(profileJobId, null, null, null), prepareCycle(profile, mode, awaited));
        }

        // ACTIVE는 세트 3번째를 꺼내는 순간 EXHAUSTED가 되므로 남은 질문이 늘 있다.
        List<PoolQuestionEntity> unused = poolQuestionRepository
                .findAllByCycleIdAndUsedAtIsNullOrderBySetNoAscQuestionIdAsc(active.getCycleId());
        int setNo = unused.getFirst().getSetNo();
        List<PoolQuestionEntity> set = unused.stream().filter(question -> question.getSetNo() == setNo).toList();

        LocalDateTime now = LocalDateTime.now();
        set.forEach(question -> {
            question.setUsedAt(now);
            question.setInterviewId(interviewId);
        });

        QuestionCycleEntity toRequest = null;
        if (set.size() == unused.size()) {
            active.setStatus(CycleStatus.EXHAUSTED);
            // 대기본이 있으면 다음 면접부터 그것을 쓴다. 없거나 실패했으면 지금 요청한다.
            if (promoteStandby(profileJobId, mode).isEmpty()) {
                toRequest = prepareCycle(profile, mode, active.getCycleNo() + 1);
            }
        } else if (setNo == 2) {
            toRequest = prepareCycle(profile, mode, active.getCycleNo() + 1);
        }
        return new Draw(drawn(profile, set), toRequest);
    }

    private AnalysisDataEntity latestCompleted(Long userId) {
        return analysisDataRepository.findLatestCompleted(userId, AnalysisResultType.PROFILE)
                .orElseThrow(QuestionPoolService::noProfile);
    }

    private static BusinessException noProfile() {
        return BusinessException.notFound("포트폴리오 분석을 먼저 진행해주세요");
    }

    private Optional<QuestionCycleEntity> lock(String profileJobId, InterviewMode mode, CycleStatus status) {
        return questionCycleRepository.findFirstByProfileJobIdAndModeAndStatusOrderByCycleNoAsc(profileJobId, mode, status);
    }

    private Optional<QuestionCycleEntity> promoteStandby(String profileJobId, InterviewMode mode) {
        return lock(profileJobId, mode, CycleStatus.STANDBY).map(cycle -> {
            cycle.setStatus(CycleStatus.ACTIVE);
            return cycle;
        });
    }

    /**
     * cycleNo 사이클을 요청할 수 있게 만들어 돌려준다. 이미 생성 중이거나 만들어져 있으면 null.
     *
     * <p>실패한 사이클과 콜백을 잃어버린 사이클은 같은 행을 다시 쓴다. 번호를 건너뛰면 세트 순서를 되짚을 수 없다.
     */
    private QuestionCycleEntity prepareCycle(AnalysisDataEntity profile, InterviewMode mode, int cycleNo) {
        LocalDateTime now = LocalDateTime.now();
        QuestionCycleEntity cycle = questionCycleRepository
                .findByProfileJobIdAndModeAndCycleNo(profile.getJobId(), mode, cycleNo)
                .orElse(null);
        if (cycle == null) {
            // ponytail: 같은 사이클을 두 요청이 동시에 처음 만들면 유니크 제약에 한쪽이 500으로 끝난다. 다시 누르면 열린다.
            return questionCycleRepository.save(QuestionCycleEntity.builder()
                    .userId(profile.getUserId())
                    .profileJobId(profile.getJobId())
                    .mode(mode)
                    .cycleNo(cycleNo)
                    .status(CycleStatus.GENERATING)
                    .requestedAt(now)
                    .build());
        }

        boolean lost = cycle.getStatus() == CycleStatus.GENERATING
                && cycle.getRequestedAt().plus(generatingTimeout).isBefore(now);
        if (cycle.getStatus() != CycleStatus.FAILED && !lost) {
            return null;
        }
        cycle.setStatus(CycleStatus.GENERATING);
        cycle.setJobId(null);
        cycle.setAttempt(1);
        cycle.setRequestNo(cycle.getRequestNo() + 1);
        cycle.setRequestedAt(now);
        cycle.setCompletedAt(null);
        cycle.setErrorMessage(null);
        return cycle;
    }

    /**
     * 자료를 새로 분석한 종합 데이터로 사이클 1을 1:1·N:1 모두 요청한다.
     *
     * <p>이전 종합 데이터의 사이클은 남은 세트와 대기본째 버린다. 콜백이 다시 와도 이미 요청한 사이클은
     * 다시 요청하지 않는다. 요청이 실패한 모드는 면접을 시작할 때 다시 요청한다.
     *
     * <p>사용자의 지금 종합 데이터일 때만 한다. 먼저 요청한 분석이 늦게 끝나거나 그 콜백이 다시 오면,
     * 그대로 진행했다가는 새 자료로 만든 사이클을 버리고 옛 자료로 질문을 만든다.
     *
     * <p>버리는 것은 이 종합 데이터보다 먼저 요청한 것의 사이클뿐이다. 지금인지 확인한 직후 더 나중에 요청한
     * 분석이 끝나 사이클을 만들 수 있어, 확인만 믿고 나머지를 모두 버리면 그 새 사이클까지 버린다.
     */
    public void startCycles(String profileJobId) {
        AnalysisDataEntity profile = analysisDataRepository.findById(profileJobId).orElse(null);
        if (profile == null || profile.getUserId() == null) {
            log.warn("주인을 알 수 없는 종합 데이터라 질문 사이클을 만들지 않습니다. profileJobId={}", profileJobId);
            return;
        }

        List<QuestionCycleEntity> toRequest = transactionTemplate.execute(status -> {
            String current = analysisDataRepository.findLatestCompleted(profile.getUserId(), AnalysisResultType.PROFILE)
                    .map(AnalysisDataEntity::getJobId)
                    .orElse(null);
            if (!profileJobId.equals(current)) {
                log.info("나중에 요청한 종합 데이터가 있어 질문 사이클을 만들지 않습니다. profileJobId={}, 지금={}",
                        profileJobId, current);
                return List.<QuestionCycleEntity>of();
            }
            int retired = questionCycleRepository.retireRequestedBefore(profile.getUserId(), profile.getCreatedAt());
            if (retired > 0) {
                log.info("자료가 바뀌어 이전 질문 사이클을 폐기합니다. userId={}, 폐기={}개", profile.getUserId(), retired);
            }
            List<QuestionCycleEntity> cycles = new ArrayList<>();
            for (InterviewMode mode : InterviewMode.values()) {
                QuestionCycleEntity cycle = prepareCycle(profile, mode, 1);
                if (cycle != null) {
                    cycles.add(cycle);
                }
            }
            return cycles;
        });

        for (QuestionCycleEntity cycle : toRequest) {
            try {
                requestCycle(cycle);
            } catch (RuntimeException ignored) {
                // 실패로 남겼다. 면접을 시작할 때 다시 요청한다.
            }
        }
    }

    /**
     * 사이클 생성을 분석 서버에 맡긴다. 보내지 못하면 실패로 남기고 예외를 그대로 던진다.
     *
     * <p>콜백 주소에 사이클 번호와 요청 차례를 싣는다. 접수 응답보다 콜백이 먼저 오면 작업 id로는 사이클을
     * 찾지 못하고, 다시 요청한 사이클에 이전 요청의 콜백이 늦게 오면 작업 id가 비어 있어 가려낼 수 없다.
     */
    private void requestCycle(QuestionCycleEntity cycle) {
        try {
            Object profile = resultField(analysisDataRepository.findById(cycle.getProfileJobId()).orElse(null), "profile");
            int excludeMax = EXCLUDE_CYCLES * SET_COUNT * setSize(cycle.getMode());
            List<String> exclude = poolQuestionRepository.findRecentQuestions(
                    cycle.getUserId(), cycle.getMode(), cycle.getCycleId(), PageRequest.of(0, excludeMax));

            GenerateResponse accepted = aiServerClient.requestQuestionCycle(QuestionCycleRequest.builder()
                    .mode(cycle.getMode().name())
                    .profile(profile)
                    .excludeQuestions(exclude)
                    .callbackUrl(callbackBaseUrl + CALLBACK_PATH
                            + "?cycleId=" + cycle.getCycleId() + "&requestNo=" + cycle.getRequestNo())
                    .build());
            if (accepted == null || accepted.getJobId() == null) {
                throw new ExternalApiException("분석 서버가 작업 번호를 돌려주지 않았습니다.", null, null);
            }
            questionCycleRepository.recordJob(cycle.getCycleId(), accepted.getJobId());
        } catch (RuntimeException e) {
            log.error("질문 사이클을 요청하지 못했습니다. cycleId={}, mode={}, cycleNo={}",
                    cycle.getCycleId(), cycle.getMode(), cycle.getCycleNo(), e);
            questionCycleRepository.markSendFailed(cycle.getCycleId(), e.getMessage(), LocalDateTime.now());
            throw e;
        }
    }

    /**
     * 질문을 기다리는 면접이 있어 실패한 사이클을 한 번 더 요청한다.
     *
     * @return 다시 요청했으면 true. 이미 한 번 다시 요청했거나 보내지 못했으면 false — 기다리는 면접을 실패로 닫는다.
     */
    public boolean retryForWaiting(Long cycleId) {
        if (questionCycleRepository.claimRetry(cycleId, MAX_ATTEMPTS_WHILE_WAITING, LocalDateTime.now()) == 0) {
            return false;
        }
        QuestionCycleEntity cycle = questionCycleRepository.findById(cycleId).orElse(null);
        if (cycle == null) {
            return false;
        }
        try {
            requestCycle(cycle);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 사이클 콜백을 반영한다. 버린 사이클이나 이미 받은 콜백이면 null.
     *
     * <p>받은 질문은 그대로 믿지 않는다. 세트 구성이 어긋나면 면접 문항 수가 들쭉날쭉해지고, 채점 기준이
     * 빈 질문은 채점할 수 없다. 그런 결과는 실패로 받는다.
     *
     * <p>같은 모드에 쓰는 사이클이 없으면 바로 쓰고, 있으면 대기본으로 둔다. 그 판단은 세트를 꺼내는 쪽과
     * 같은 행 잠금을 거쳐, 마지막 세트를 꺼내는 중에 도착한 사이클이 대기본으로 묻히지 않게 한다.
     */
    @Transactional
    public CycleOutcome applyCycleResult(QuestionCycleCallbackRequest request, Long cycleId, Integer requestNo) {
        QuestionCycleEntity cycle = (cycleId != null
                ? questionCycleRepository.lockById(cycleId)
                : Optional.ofNullable(request.getJobId())
                        .flatMap(questionCycleRepository::findByJobId)
                        .flatMap(found -> questionCycleRepository.lockById(found.getCycleId())))
                .orElse(null);
        if (cycle == null) {
            log.warn("알 수 없는 질문 사이클 콜백을 받았습니다. jobId={}, cycleId={}", request.getJobId(), cycleId);
            return null;
        }
        if ((requestNo != null && !requestNo.equals(cycle.getRequestNo()))
                || (cycle.getJobId() != null && !cycle.getJobId().equals(request.getJobId()))) {
            log.info("다시 요청하기 전 작업의 콜백이라 버립니다. cycleId={}, jobId={}, requestNo={}, 지금 requestNo={}",
                    cycle.getCycleId(), request.getJobId(), requestNo, cycle.getRequestNo());
            return null;
        }
        if (cycle.getStatus() != CycleStatus.GENERATING) {
            // 자료가 바뀌어 폐기했거나 이미 받은 콜백이다.
            log.info("생성 중이 아닌 사이클의 콜백이라 버립니다. cycleId={}, status={}", cycle.getCycleId(), cycle.getStatus());
            return null;
        }

        cycle.setJobId(request.getJobId());
        cycle.setCompletedAt(LocalDateTime.now());

        String error = STATUS_SUCCEEDED.equalsIgnoreCase(request.getStatus())
                ? invalidReason(cycle.getMode(), request.getResult())
                : failureMessage(request.getError());
        if (error != null) {
            log.warn("질문 사이클을 만들지 못했습니다. cycleId={}, mode={}, cycleNo={}, 사유={}",
                    cycle.getCycleId(), cycle.getMode(), cycle.getCycleNo(), error);
            cycle.setStatus(CycleStatus.FAILED);
            cycle.setErrorMessage(error);
            return new CycleOutcome(cycle.getCycleId(), cycle.getUserId(), cycle.getMode(), false, error);
        }

        poolQuestionRepository.saveAll(request.getResult().getQuestions().stream()
                .sorted(Comparator.comparing(QuestionCycleCallbackRequest.Question::getSetNo))
                .map(question -> PoolQuestionEntity.builder()
                        .cycleId(cycle.getCycleId())
                        .setNo(question.getSetNo())
                        .category(question.getCategory())
                        .question(question.getQuestion())
                        .intention(question.getIntention())
                        .expectedAnswer(question.getExpectedAnswer())
                        .basedOn(question.getBasedOn())
                        .build())
                .toList());

        boolean hasActive = lock(cycle.getProfileJobId(), cycle.getMode(), CycleStatus.ACTIVE).isPresent();
        cycle.setStatus(hasActive ? CycleStatus.STANDBY : CycleStatus.ACTIVE);
        cycle.setErrorMessage(null);
        return new CycleOutcome(cycle.getCycleId(), cycle.getUserId(), cycle.getMode(), true, null);
    }

    /**
     * 기다리기로 한 사이 그 사이클이 끝났는지.
     *
     * <p>사이클 콜백은 기다리는 면접 준비를 찾아 이어준다. 그런데 준비가 기다리기로 정하고 그 표시를 남기기
     * 전에 콜백이 지나가면 아무도 이어주지 않는다. 표시를 남긴 뒤 한 번 더 본다.
     */
    @Transactional(readOnly = true)
    public boolean settled(Long userId, InterviewMode mode) {
        // 종합 데이터를 분석하는 중이면 사이클은 아직 요청되지도 않았다. 이전 자료의 사이클을 보고 이어가면
        // 다시 기다리기로 돌아와 이어가기를 되풀이한다.
        boolean analyzing = analysisDataRepository
                .findTopByUserIdAndResultTypeOrderByCreatedAtDesc(userId, AnalysisResultType.PROFILE)
                .map(aiMetaDataService::isAnalyzing)
                .orElse(false);
        if (analyzing) {
            return false;
        }
        return analysisDataRepository.findLatestCompleted(userId, AnalysisResultType.PROFILE)
                .flatMap(profile -> questionCycleRepository.findTopByProfileJobIdAndModeOrderByCycleNoDesc(profile.getJobId(), mode))
                .map(cycle -> cycle.getStatus() != CycleStatus.GENERATING)
                .orElse(false);
    }

    /**
     * 사이클을 기다리는 준비를 닫을 사유. 더 기다릴 만하면 null.
     *
     * <p>사이클은 종합 데이터가 나온 뒤에야 요청된다. 그 분석을 기다리는 동안은 대기 시간을 세지 않고, 분석이
     * 끝난 때부터 센다. 처음 면접을 시작하며 자료 분석부터 한 사용자는 분석만으로 대기 시간을 넘긴다.
     * 분석이 실패했으면 더 기다려도 올 질문이 없다.
     *
     * @param profileJobId 기다리는 준비가 바라보는 종합 데이터
     * @param waitingSince 기다리기 시작한 시각
     */
    @Transactional(readOnly = true)
    public String waitingFailure(String profileJobId, LocalDateTime waitingSince, Duration timeout) {
        AnalysisDataEntity profile = profileJobId == null ? null
                : analysisDataRepository.findById(profileJobId).orElse(null);
        LocalDateTime since = waitingSince;
        if (profile != null) {
            if (aiMetaDataService.isAnalyzing(profile)) {
                return null;
            }
            if (profile.getStatus() == AnalysisStatus.FAILED) {
                return profile.getErrorMessage() == null
                        ? "자료를 분석하지 못해 질문을 준비하지 못했습니다."
                        : "자료를 분석하지 못해 질문을 준비하지 못했습니다. " + profile.getErrorMessage();
            }
            if (profile.getCompletedAt() != null && since != null && profile.getCompletedAt().isAfter(since)) {
                since = profile.getCompletedAt();
            }
        }
        if (since == null || since.plus(timeout).isAfter(LocalDateTime.now())) {
            return null;
        }
        return "새 질문을 제때 준비하지 못했습니다. 잠시 후 다시 시도해주세요.";
    }

    private String invalidReason(InterviewMode mode, QuestionCycleCallbackRequest.Result result) {
        List<QuestionCycleCallbackRequest.Question> questions =
                result == null || result.getQuestions() == null ? List.of() : result.getQuestions();
        Map<Integer, Integer> perSet = new HashMap<>();
        for (QuestionCycleCallbackRequest.Question question : questions) {
            // 모범답안도 본다. N:1 재작성 요청은 이 값이 비면 통째로 거부된다.
            if (question.getSetNo() == null || isBlank(question.getCategory()) || isBlank(question.getQuestion())
                    || isBlank(question.getIntention()) || isBlank(question.getExpectedAnswer())) {
                return "세트 번호·카테고리·본문·채점 기준·모범답안이 빈 질문이 있습니다.";
            }
            perSet.merge(question.getSetNo(), 1, Integer::sum);
        }
        if (!perSet.keySet().equals(Set.of(1, 2, 3))
                || perSet.values().stream().anyMatch(count -> count != setSize(mode))) {
            return "세트 구성이 맞지 않습니다. 세트 " + SET_COUNT + "개에 " + setSize(mode) + "문항씩이어야 합니다. 받은 구성="
                    + perSet;
        }
        return null;
    }

    private String failureMessage(QuestionCycleCallbackRequest.Error error) {
        if (error == null) {
            return "질문 사이클을 만들지 못했습니다.";
        }
        return error.getStatusCode() + " " + error.getMessage();
    }

    private DrawnSet drawn(AnalysisDataEntity profile, List<PoolQuestionEntity> set) {
        List<TailoredQuestionResponse> questions = new ArrayList<>();
        for (int i = 0; i < set.size(); i++) {
            PoolQuestionEntity question = set.get(i);
            questions.add(TailoredQuestionResponse.builder()
                    // 면접 안의 지역 번호. 채팅 서버는 양수 번호를 원질문으로 읽는다.
                    .id(i + 1)
                    .category(question.getCategory())
                    .question(question.getQuestion())
                    .intention(question.getIntention())
                    .expectedAnswer(question.getExpectedAnswer())
                    .basedOn(question.getBasedOn())
                    .build());
        }
        Object major = resultField(profile, "profile") instanceof Map<?, ?> body ? body.get("major") : null;
        return new DrawnSet(profile.getJobId(), questions, resultField(profile, "projectSummary"),
                major instanceof String text ? text : null);
    }

    private Object resultField(AnalysisDataEntity data, String key) {
        return data != null && data.getResult() instanceof Map<?, ?> result ? result.get(key) : null;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
