package repit.repit_api_server.global.client;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;
import repit.repit_api_server.domain.metadata.dto.request.GenerateRequest;
import repit.repit_api_server.domain.userdata.analysis.dto.request.AudioAnalysisRequest;
import repit.repit_api_server.domain.userdata.analysis.dto.response.AudioAnalysisAcceptedResponse;
import repit.repit_api_server.domain.userdata.analysis.dto.response.AudioAnalysisJobResponse;
import repit.repit_api_server.domain.userdata.analysis.dto.response.VideoAnalysisAcceptedResponse;
import repit.repit_api_server.domain.userdata.analysis.dto.response.VideoAnalysisJobResponse;
import repit.repit_api_server.domain.metadata.dto.request.MetaDataRequest;
import repit.repit_api_server.domain.metadata.dto.response.GenerateResponse;
import repit.repit_api_server.domain.metadata.dto.response.MetaDataResponse;
import repit.repit_api_server.domain.userdata.feedback.dto.request.FeedbackMultiRequest;
import repit.repit_api_server.domain.userdata.feedback.dto.request.FeedbackSoloRequest;
import repit.repit_api_server.domain.userdata.feedback.dto.response.FeedbackAcceptedResponse;
import repit.repit_api_server.domain.userdata.question.dto.request.QuestionTailorMultiRequest;
import repit.repit_api_server.domain.userdata.question.dto.request.QuestionTailorRequest;
import repit.repit_api_server.domain.userdata.question.dto.response.QuestionResponse;
import repit.repit_api_server.domain.userdata.question.dto.response.QuestionTailorAcceptedResponse;
import repit.repit_api_server.global.common.ApiResponse;

import java.util.Map;

public interface AiServerApi {

    @PostExchange("/api/v1/ai/createMetaData")
    MetaDataResponse createMetaData(@RequestHeader("Authorization") String authorization,
                                    @RequestBody MetaDataRequest request);

    @GetExchange("/api/v1/ai/createQuestion")
    ApiResponse<QuestionResponse> createQuestion();

    @PostExchange("/generate")
    GenerateResponse generate(@RequestBody GenerateRequest request);

    @PostExchange("/generate-mock")
    GenerateResponse generateMock(@RequestBody GenerateRequest request);

    // 비동기 채점. 202로 접수만 되고 결과는 callbackUrl로 POST된다.
    @PostExchange("/feedback/solo")
    FeedbackAcceptedResponse requestSoloFeedback(@RequestBody FeedbackSoloRequest request);

    // N:1 채점. 면접관별 평가가 함께 돌아온다는 점만 다르고 접수·콜백 방식은 같다.
    @PostExchange("/feedback/multi")
    FeedbackAcceptedResponse requestMultiFeedback(@RequestBody FeedbackMultiRequest request);

    // 답변 음성 분석. 채점과 따로 돈다. 202로 접수만 되고 결과는 callbackUrl로 POST된다.
    @PostExchange("/analysis/audio")
    AudioAnalysisAcceptedResponse requestAudioAnalysis(@RequestBody AudioAnalysisRequest request);

    // 맡긴 음성 분석의 진행과 결과. 콜백을 끝내 받지 못했을 때 결과를 여기서 가져온다.
    @GetExchange("/analysis/audio/jobs/{jobId}")
    AudioAnalysisJobResponse getAudioAnalysisJob(@PathVariable String jobId);

    // 면접 화면 전체 녹화 분석. 음성 분석과 따로 돈다. 같은 요청을 다시 보낼 때 내용이 같아야 하므로
    // 처음 보낸 본문을 저장해 두고 그대로 싣는다.
    @PostExchange("/analysis/video")
    VideoAnalysisAcceptedResponse requestVideoAnalysis(@RequestBody Map<String, Object> request);

    // 맡긴 영상 분석의 진행과 결과. 콜백이 오지 않을 때 결과를 여기서 가져온다.
    @GetExchange("/analysis/video/jobs/{jobId}")
    VideoAnalysisJobResponse getVideoAnalysisJob(@PathVariable String jobId);

    // 면접 시작 직전 원질문 재작성. 마찬가지로 202 접수 후 결과는 콜백으로 온다.
    @PostExchange("/questions/tailor")
    QuestionTailorAcceptedResponse tailorQuestions(@RequestBody QuestionTailorRequest request);

    // N:1 질문 구성. 기술 원질문 재작성과 비개발 면접관 질문 생성이 한 번에 돈다.
    @PostExchange("/questions/tailor/multi")
    QuestionTailorAcceptedResponse tailorQuestionsMulti(@RequestBody QuestionTailorMultiRequest request);
}
