package repit.repit_api_server.domain.metadata.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import repit.repit_api_server.domain.metadata.dto.request.CallbackSuccessRequest;
import repit.repit_api_server.domain.metadata.dto.response.ResultResponse;
import repit.repit_api_server.domain.metadata.entity.AnalysisDataEntity;
import repit.repit_api_server.domain.metadata.repository.AnalysisDataRepository;

@Service
@RequiredArgsConstructor
public class AiMetaDataService {
    private final AnalysisDataRepository analysisDataRepository;

    // 분석 요청 시점에 작업 소유자를 먼저 기록해둔다. 결과는 콜백에서 채워진다.
    public void registerJob(String jobId, Long userId) {
        if (jobId == null || userId == null) {
            return;
        }
        AnalysisDataEntity data = analysisDataRepository.findById(jobId)
                .orElseGet(() -> AnalysisDataEntity.builder().jobId(jobId).build());
        data.setUserId(userId);
        analysisDataRepository.save(data);
    }

    @Transactional
    public void saveResult(CallbackSuccessRequest request) {
        // registerJob으로 이미 저장된 행이 있으면 userId를 유지한 채 결과만 채운다.
        AnalysisDataEntity data = analysisDataRepository.findById(request.getJob_id())
                .orElseGet(() -> AnalysisDataEntity.builder().jobId(request.getJob_id()).build());
        data.setResult(request.getResult());
        analysisDataRepository.save(data);
    }

    public ResultResponse getResult(String jobId) {
        return analysisDataRepository.findById(jobId)
                .map(data -> new ResultResponse(data.getResult()))
                .orElseGet(() -> new ResultResponse(null));
    }
}
