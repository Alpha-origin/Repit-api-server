package repit.repit_api_server.domain.userdata.analysis.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import repit.repit_api_server.domain.userdata.analysis.dto.request.AudioAnalysisCallbackRequest;
import repit.repit_api_server.domain.userdata.analysis.service.AudioAnalysisService;

@RestController
@RequestMapping("/api/analyses")
@RequiredArgsConstructor
public class AudioAnalysisController {

    private final AudioAnalysisService audioAnalysisService;

    // 분석 서버 전용 콜백. 2xx가 늦거나 실패하면 결과가 폐기되므로 그대로 저장만 하고 응답한다.
    @PostMapping("/audio/callback")
    public ResponseEntity<Void> audioCallback(@RequestBody AudioAnalysisCallbackRequest request) {
        audioAnalysisService.handleCallback(request);
        return ResponseEntity.ok().build();
    }
}
