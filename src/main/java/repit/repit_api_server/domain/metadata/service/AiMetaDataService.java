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

@Service
@RequiredArgsConstructor
public class AiMetaDataService {

    private static final Logger log = LoggerFactory.getLogger(AiMetaDataService.class);

    private static final String STATUS_SUCCEEDED = "succeeded";
    private static final String STATUS_FAILED = "failed";

    private final AnalysisDataRepository analysisDataRepository;

    /**
     * 분석 요청 시점에 작업 소유자를 먼저 기록해둔다. 결과는 콜백에서 채워진다.
     *
     * <p>분석 서버가 빠르면 이 메서드보다 콜백이 먼저 도착할 수 있다. 그때 행 전체를 저장하면
     * 아직 비어 있는 result로 이미 받아둔 결과를 덮어쓰게 되므로, 소유자 컬럼만 갱신하고
     * 행이 없을 때만 새로 만든다.
     */
    @Transactional
    public void registerJob(String jobId, Long userId) {
        if (jobId == null || userId == null) {
            return;
        }
        if (analysisDataRepository.updateUserId(jobId, userId) == 0) {
            analysisDataRepository.save(AnalysisDataEntity.builder()
                    .jobId(jobId)
                    .userId(userId)
                    .build());
        }
    }

    /**
     * 분석 결과 콜백을 저장한다. 재전송이 있을 수 있어 두 번 받아도 안전해야 한다.
     *
     * <p>실패 콜백에는 result가 없다. status를 보지 않고 그대로 덮어쓰면 먼저 받아둔 성공
     * 결과까지 지워지므로, 성공 콜백일 때만 결과를 저장한다.
     */
    @Transactional
    public void saveResult(CallbackSuccessRequest request) {
        String jobId = request.getJob_id();
        if (jobId == null) {
            log.warn("job_id 없는 분석 콜백을 받았습니다. status={}", request.getStatus());
            return;
        }

        // registerJob으로 이미 저장된 행이 있으면 userId를 유지한 채 결과만 채운다.
        AnalysisDataEntity data = analysisDataRepository.findById(jobId)
                .orElseGet(() -> AnalysisDataEntity.builder().jobId(jobId).build());

        if (STATUS_SUCCEEDED.equalsIgnoreCase(request.getStatus()) && request.getResult() != null) {
            data.setStatus(AnalysisStatus.SUCCEEDED);
            data.setResult(request.getResult());
            data.setErrorStatusCode(null);
            data.setErrorMessage(null);
            analysisDataRepository.save(data);
            return;
        }

        // 이미 결과를 받아둔 작업이라면 뒤늦은 실패 콜백에 그 결과를 잃을 이유가 없다.
        if (data.getStatus() == AnalysisStatus.SUCCEEDED) {
            log.warn("이미 성공한 분석에 실패 콜백이 도착해 무시합니다. jobId={}, error={}",
                    jobId, describeError(request.getError()));
            return;
        }

        log.warn("분석에 실패했습니다. jobId={}, status={}, error={}",
                jobId, request.getStatus(), describeError(request.getError()));
        data.setStatus(AnalysisStatus.FAILED);
        if (request.getError() != null) {
            data.setErrorStatusCode(request.getError().getStatus_code());
            data.setErrorMessage(request.getError().getMessage());
        }
        analysisDataRepository.save(data);
    }

    /**
     * 이미 끝난 작업이면 콜백과 같은 모양으로 돌려준다. 아직 진행 중이거나 모르는 작업이면 null.
     *
     * <p>구독이 콜백보다 늦게 붙는 경우가 있다. 그때 SSE로 흘릴 것이 없으면 클라이언트는
     * 영영 아무것도 받지 못하므로, 저장해둔 결과로 되짚어준다.
     */
    @Transactional(readOnly = true)
    public CallbackSuccessResponse findFinished(String jobId) {
        return analysisDataRepository.findById(jobId)
                .filter(data -> data.getStatus() != AnalysisStatus.PENDING)
                .map(data -> CallbackSuccessResponse.builder()
                        .job_id(data.getJobId())
                        .status(data.getStatus() == AnalysisStatus.SUCCEEDED ? STATUS_SUCCEEDED : STATUS_FAILED)
                        .result(data.getResult())
                        .error(toError(data))
                        .build())
                .orElse(null);
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
     */
    public ResultResponse getResult(String jobId) {
        return new ResultResponse(analysisDataRepository.findById(jobId)
                .map(AnalysisDataEntity::getResult)
                .orElse(null));
    }
}
