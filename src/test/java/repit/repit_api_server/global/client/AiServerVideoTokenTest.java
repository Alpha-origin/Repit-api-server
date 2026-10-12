package repit.repit_api_server.global.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import repit.repit_api_server.domain.userdata.analysis.dto.request.AudioAnalysisRequest;

import java.util.Map;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 분석 서버는 영상 요청·조회에서 X-Internal-Token을 본문보다 먼저 검사한다. 없으면 모든 접수가 401로 닫힌다.
 * 다른 분석 서버 호출에는 싣지 않는다 — 영상 인증값이 그쪽으로 새면 안 된다.
 */
class AiServerVideoTokenTest {

    @Test
    void 영상_요청과_조회에만_영상_인증값을_싣는다() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ai.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AiServerClient client = new AiServerClient(HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(builder.build())).build()
                .createClient(AiServerApi.class), new ExternalApiExecutor());
        ReflectionTestUtils.setField(client, "videoApiToken", "video-secret");

        server.expect(requestTo("http://ai.test/analysis/video")).andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Token", "video-secret"))
                .andRespond(withSuccess("{\"jobId\":\"job-1\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://ai.test/analysis/video/jobs/job-1")).andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Token", "video-secret"))
                .andRespond(withSuccess("{\"jobId\":\"job-1\",\"status\":\"processing\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://ai.test/analysis/audio")).andExpect(method(HttpMethod.POST))
                .andExpect(headerDoesNotExist("X-Internal-Token"))
                .andRespond(withSuccess("{\"jobId\":\"job-2\"}", MediaType.APPLICATION_JSON));

        client.requestVideoAnalysis(Map.of("requestId", "video-1"));
        client.getVideoAnalysisJob("job-1");
        client.requestAudioAnalysis(AudioAnalysisRequest.builder().requestId("audio-1").build());

        server.verify();
    }
}
