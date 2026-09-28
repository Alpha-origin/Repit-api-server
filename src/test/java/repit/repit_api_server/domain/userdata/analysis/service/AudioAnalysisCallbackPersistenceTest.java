package repit.repit_api_server.domain.userdata.analysis.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import repit.repit_api_server.domain.userdata.analysis.dto.request.AudioAnalysisCallbackRequest;
import repit.repit_api_server.domain.userdata.analysis.entity.AudioAnalysisEntity;
import repit.repit_api_server.domain.userdata.analysis.entity.AudioAnalysisResultEntity;
import repit.repit_api_server.domain.userdata.analysis.entity.enums.AudioAnalysisStatus;
import repit.repit_api_server.domain.userdata.analysis.repository.AudioAnalysisRepository;
import repit.repit_api_server.domain.userdata.analysis.repository.AudioAnalysisResultRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 음성 분석 콜백이 실제 DB의 각 컬럼까지 들어가는지 확인한다.
 *
 * <p>jsonb 직렬화나 컬럼 이름이 어긋나는 것은 플러시 시점에야 드러난다. 실제 스키마에 쓰고 영속성
 * 컨텍스트를 비운 뒤 다시 읽는다. 테스트 트랜잭션은 끝나면 롤백된다.
 */
@SpringBootTest
@Transactional
class AudioAnalysisCallbackPersistenceTest {

    @Autowired
    private AudioAnalysisService service;
    @Autowired
    private AudioAnalysisRepository analysisRepository;
    @Autowired
    private AudioAnalysisResultRepository resultRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private final String requestId = "audio-" + UUID.randomUUID();

    private static Map<String, Object> errorOf(String code, boolean retryable) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", "음성 분석의 일부 작업을 완료하지 못했습니다.");
        error.put("retryable", retryable);
        return error;
    }

    @Test
    void 콜백의_상태와_녹음별_결과가_jsonb까지_저장되고_재전송에도_한_벌만_남는다() {
        AudioAnalysisEntity pending = analysisRepository.save(AudioAnalysisEntity.builder()
                .interviewId(999_999L).userId(9L).sessionId("b1c2d3")
                .requestId(requestId).status(AudioAnalysisStatus.PENDING).recordingIds(List.of(301L, 302L))
                .build());

        AudioAnalysisCallbackRequest callback = new AudioAnalysisCallbackRequest(
                "job-" + requestId, requestId, "b1c2d3", "999999", "9", "partial", List.of(
                new AudioAnalysisCallbackRequest.Result("301", "101", "201", "ready", 120000L,
                        Map.of("status", "ready", "data", Map.of("wpm", 132, "silenceRatio", 0.12), "error", "none"),
                        Map.of("status", "ready", "data", Map.of("fillerCount", 3)),
                        null),
                new AudioAnalysisCallbackRequest.Result("302", "102", "202", "unavailable", null,
                        null, null, errorOf("SOURCE_NOT_FOUND", false))));

        service.handleCallback(callback);
        service.handleCallback(callback);
        entityManager.flush();
        entityManager.clear();

        AudioAnalysisEntity stored = analysisRepository.findById(pending.getAnalysisId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AudioAnalysisStatus.PARTIAL);
        assertThat(stored.getJobId()).isEqualTo("job-" + requestId);
        assertThat(stored.getRecordingIds()).containsExactly(301L, 302L);

        List<AudioAnalysisResultEntity> results =
                resultRepository.findAllByAnalysisIdOrderByResultIdAsc(pending.getAnalysisId());
        assertThat(results).hasSize(2);

        AudioAnalysisResultEntity first = results.getFirst();
        assertThat(first.getRecordingId()).isEqualTo(301L);
        assertThat(first.getQuestionId()).isEqualTo(101L);
        assertThat(first.getAnswerId()).isEqualTo(201L);
        assertThat(first.getStatus()).isEqualTo("READY");
        assertThat(first.getDurationMs()).isEqualTo(120000L);
        assertThat(first.getTiming()).containsEntry("status", "ready");
        assertThat(first.getTiming().get("data")).isEqualTo(Map.of("wpm", 132, "silenceRatio", 0.12));
        assertThat(first.getFluency()).containsEntry("status", "ready");

        AudioAnalysisResultEntity second = results.get(1);
        assertThat(second.getDurationMs()).isNull();
        assertThat(second.getTiming()).isNull();
        assertThat(second.getError()).contains("SOURCE_NOT_FOUND");
        assertThat(second.getErrorCode()).isEqualTo("SOURCE_NOT_FOUND");
        assertThat(second.getErrorRetryable()).isFalse();
    }
}
