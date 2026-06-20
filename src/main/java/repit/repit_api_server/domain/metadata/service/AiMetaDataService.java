package repit.repit_api_server.domain.metadata.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import repit.repit_api_server.domain.metadata.dto.request.CallbackSuccessRequest;
import repit.repit_api_server.domain.metadata.entity.AnalysisDataEntity;
import repit.repit_api_server.domain.metadata.repository.AnalysisDataRepository;

@Service
@RequiredArgsConstructor
public class AiMetaDataService {
    private final AnalysisDataRepository analysisDataRepository;

    public void saveResult(CallbackSuccessRequest request) {
        AnalysisDataEntity data = AnalysisDataEntity.builder()
                .jobId(request.getJob_id())
                .result(request.getResult())
                .build();
        analysisDataRepository.save(data);
    }
}
