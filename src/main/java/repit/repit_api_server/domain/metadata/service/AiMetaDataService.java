package repit.repit_api_server.domain.metadata.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import repit.repit_api_server.domain.metadata.dto.request.CallbackSuccessRequest;
import repit.repit_api_server.domain.metadata.dto.response.ResultResponse;
import repit.repit_api_server.domain.metadata.entity.AnalysisDataEntity;
import repit.repit_api_server.domain.metadata.repository.AnalysisDataRepository;

@Service
@RequiredArgsConstructor
public class AiMetaDataService {

    private static final Logger log = LoggerFactory.getLogger(AiMetaDataService.class);

    private static final String STATUS_SUCCEEDED = "succeeded";

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

        if (!STATUS_SUCCEEDED.equalsIgnoreCase(request.getStatus()) || request.getResult() == null) {
            log.warn("분석 결과를 저장하지 않았습니다. jobId={}, status={}, error={}",
                    jobId, request.getStatus(), describeError(request.getError()));
            return;
        }

        // registerJob으로 이미 저장된 행이 있으면 userId를 유지한 채 결과만 채운다.
        AnalysisDataEntity data = analysisDataRepository.findById(jobId)
                .orElseGet(() -> AnalysisDataEntity.builder().jobId(jobId).build());
        data.setResult(request.getResult());
        analysisDataRepository.save(data);
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
