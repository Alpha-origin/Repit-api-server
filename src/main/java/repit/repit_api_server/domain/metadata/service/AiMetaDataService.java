package repit.repit_api_server.domain.metadata.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import repit.repit_api_server.domain.metadata.dto.request.CallbackSuccessRequest;
import repit.repit_api_server.domain.metadata.dto.response.CallbackSuccessResponse;
import repit.repit_api_server.domain.metadata.dto.response.ResultResponse;
import repit.repit_api_server.domain.metadata.entity.AnalysisDataEntity;
import repit.repit_api_server.domain.metadata.entity.enums.AnalysisStatus;
import repit.repit_api_server.domain.metadata.repository.AnalysisDataRepository;
import repit.repit_api_server.global.exception.BusinessException;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AiMetaDataService {

    private static final Logger log = LoggerFactory.getLogger(AiMetaDataService.class);

    private static final String STATUS_SUCCEEDED = "succeeded";
    private static final String STATUS_FAILED = "failed";
    private static final String STATUS_PENDING = "pending";

    private final AnalysisDataRepository analysisDataRepository;

    /**
     * 분석 요청 시점에 이번 실행을 접수한다. 소유자를 기록하고, 지난 실행에 남은 결과를 걷어낸다.
     *
     * <p>분석 서버는 같은 jobId를 다시 내려줄 수 있다. 그 행에 지난 실행의 결과가 그대로 남아
     * 있으면, 구독이 붙는 순간 콜백이 오기도 전에 옛 결과가 완료 이벤트로 나간다. 클라이언트는
     * 새 분석이 끝난 줄 알고 지난 결과를 집어 든다.
     *
     * <p>분석 서버가 빠르면 이 메서드보다 콜백이 먼저 도착할 수 있다. 그 결과까지 지우면
     * 방금 받아둔 결과를 잃으므로, {@code requestedAt}(분석 서버에 요청을 보내기 직전 시각)보다
     * 나중에 끝난 결과는 이번 실행의 것으로 보고 그대로 둔다.
     *
     * <p>행 전체를 저장하는 대신 조건을 담은 갱신을 쓰는 것도 같은 이유다. 읽고 쓰는 사이에
     * 도착한 콜백을 아직 비어 있는 result로 덮어쓰지 않는다.
     */
    @Transactional
    public void registerJob(String jobId, Long userId, LocalDateTime requestedAt) {
        if (jobId == null) {
            return;
        }

        LocalDateTime boundary = requestedAt == null ? LocalDateTime.now() : requestedAt;
        boolean cleared = analysisDataRepository.clearPreviousRun(jobId, AnalysisStatus.PENDING, boundary) > 0;

        // 걷어낸 것도 없고 행도 없다면 이번 실행이 처음이다.
        if (!cleared && !analysisDataRepository.existsById(jobId)) {
            analysisDataRepository.save(AnalysisDataEntity.builder()
                    .jobId(jobId)
                    .userId(userId)
                    .build());
            return;
        }

        if (userId != null) {
            analysisDataRepository.updateUserId(jobId, userId);
        }
    }

    /**
     * 분석 결과 콜백을 저장하고, 저장된 상태를 돌려준다. 재전송이 있을 수 있어 두 번 받아도 안전해야 한다.
     *
     * <p>실패 콜백에는 result가 없다. status를 보지 않고 그대로 덮어쓰면 먼저 받아둔 성공
     * 결과까지 지워지므로, 성공 콜백일 때만 결과를 저장한다.
     *
     * <p>돌려주는 값은 요청을 되비춘 것이 아니라 DB에 남은 것이다. 이 메서드는 요청을 여러 갈래로
     * 걸러내므로, 요청을 그대로 흘려보내면 저장이 걸러낸 값까지 구독으로 새어나간다. 클라이언트는
     * DB에 없는 성공을 받아들고 결과를 조회하다 빈손이 된다.
     *
     * <p>저장하지 못했으면 아무것도 돌려주지 않는다. 흘려보낼 상태가 없다는 뜻이다.
     */
    @Transactional
    public CallbackSuccessResponse saveResult(CallbackSuccessRequest request) {
        String jobId = request.getJob_id();
        if (jobId == null) {
            log.warn("job_id 없는 분석 콜백을 받았습니다. status={}", request.getStatus());
            return null;
        }

        // registerJob으로 이미 저장된 행이 있으면 userId를 유지한 채 결과만 채운다.
        AnalysisDataEntity data = analysisDataRepository.findById(jobId)
                .orElseGet(() -> AnalysisDataEntity.builder().jobId(jobId).build());

        if (STATUS_SUCCEEDED.equalsIgnoreCase(request.getStatus()) && request.getResult() != null) {
            data.setStatus(AnalysisStatus.SUCCEEDED);
            data.setResult(request.getResult());
            data.setErrorStatusCode(null);
            data.setErrorMessage(null);
            data.setCompletedAt(LocalDateTime.now());
            return toResponse(analysisDataRepository.save(data));
        }

        // 이미 결과를 받아둔 작업이라면 뒤늦은 실패 콜백에 그 결과를 잃을 이유가 없다.
        if (data.getStatus() == AnalysisStatus.SUCCEEDED) {
            log.warn("이미 성공한 분석에 실패 콜백이 도착해 무시합니다. jobId={}, error={}",
                    jobId, describeError(request.getError()));
            // 지켜낸 성공을 그대로 돌려준다. 흘려보낼 것이 있다면 그건 이 실패가 아니라 저 성공이다.
            return toResponse(data);
        }

        log.warn("분석에 실패했습니다. jobId={}, status={}, error={}",
                jobId, request.getStatus(), describeError(request.getError()));
        data.setStatus(AnalysisStatus.FAILED);
        if (request.getError() != null) {
            data.setErrorStatusCode(request.getError().getStatus_code());
            data.setErrorMessage(request.getError().getMessage());
        }
        data.setCompletedAt(LocalDateTime.now());
        return toResponse(analysisDataRepository.save(data));
    }

    /**
     * 이미 끝난 작업이면 콜백과 같은 모양으로 돌려준다. 아직 진행 중이거나 모르는 작업이면 null.
     *
     * <p>구독이 콜백보다 늦게 붙는 경우가 있다. 그때 SSE로 흘릴 것이 없으면 클라이언트는
     * 영영 아무것도 받지 못하므로, 저장해둔 결과로 되짚어준다.
     *
     * <p>끝났다는 판정은 SUCCEEDED와 FAILED에만 준다. "PENDING이 아니면 끝난 것"으로 보면
     * 상태를 알 수 없는 행이 완료로 새어나가, 콜백이 오기도 전에 실패 이벤트가 나간다.
     */
    @Transactional(readOnly = true)
    public CallbackSuccessResponse findFinished(String jobId) {
        return analysisDataRepository.findById(jobId)
                .filter(data -> data.getStatus() == AnalysisStatus.SUCCEEDED
                        || data.getStatus() == AnalysisStatus.FAILED)
                .map(this::toResponse)
                .orElse(null);
    }

    /**
     * 저장된 행을 구독으로 흘려보낼 모양으로 옮긴다.
     *
     * <p>콜백 전송과 구독 시점 되짚기가 같은 변환을 거치게 해, 어느 경로로 받든 클라이언트가
     * 보는 내용이 같도록 한다.
     */
    private CallbackSuccessResponse toResponse(AnalysisDataEntity data) {
        return CallbackSuccessResponse.builder()
                .job_id(data.getJobId())
                .status(statusName(data.getStatus()))
                .result(data.getResult())
                .error(toError(data))
                .build();
    }

    private CallbackSuccessRequest.Error toError(AnalysisDataEntity data) {
        if (data.getErrorStatusCode() == null && data.getErrorMessage() == null) {
            return null;
        }
        return CallbackSuccessRequest.Error.builder()
                .status_code(data.getErrorStatusCode())
                .message(data.getErrorMessage())
                .build();
    }

    private String describeError(CallbackSuccessRequest.Error error) {
        if (error == null) {
            return null;
        }
        return error.getStatus_code() + " " + error.getMessage();
    }

    /**
     * 저장된 분석 결과를 원형 그대로 돌려준다.
     * 면접에 쓸 질문은 재작성이 끝나는 시점에 이 서버가 채팅 서버로 직접 넘기므로,
     * 여기서 재작성본을 끼워넣지 않는다.
     *
     * <p>모르는 jobId를 빈 결과로 돌려주지 않는다. 그러면 호출자는 "아직 끝나지 않은 분석"과
     * "존재하지 않는 작업"을 똑같은 {@code result: null}로 받아, 잘못된 jobId로 조회하고 있다는
     * 사실을 알 수 없다. 아직 결과가 없는 경우도 상태를 함께 실어 이유가 드러나게 한다.
     */
    @Transactional(readOnly = true)
    public ResultResponse getResult(String jobId) {
        AnalysisDataEntity data = analysisDataRepository.findById(jobId)
                .orElseThrow(() -> BusinessException.notFound("분석 결과를 찾을 수 없습니다. jobId=" + jobId));

        if (data.getResult() == null) {
            log.warn("결과가 아직 없는 분석을 조회했습니다. jobId={}, status={}", jobId, data.getStatus());
        }

        return ResultResponse.builder()
                .jobId(data.getJobId())
                .status(statusName(data.getStatus()))
                .result(data.getResult())
                .error(toError(data))
                .build();
    }

    private String statusName(AnalysisStatus status) {
        if (status == AnalysisStatus.SUCCEEDED) {
            return STATUS_SUCCEEDED;
        }
        if (status == AnalysisStatus.FAILED) {
            return STATUS_FAILED;
        }
        return STATUS_PENDING;
    }
}
