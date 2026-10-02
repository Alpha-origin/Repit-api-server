package repit.repit_api_server.global.client;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import repit.repit_api_server.domain.metadata.dto.request.GenerateRequest;
import repit.repit_api_server.domain.metadata.dto.request.ProfileRequest;
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
import repit.repit_api_server.domain.userdata.question.dto.request.QuestionCycleRequest;
import repit.repit_api_server.domain.userdata.question.dto.request.QuestionTailorMultiRequest;
import repit.repit_api_server.domain.userdata.question.dto.request.QuestionTailorRequest;
import repit.repit_api_server.domain.userdata.question.dto.response.QuestionResponse;
import repit.repit_api_server.domain.userdata.question.dto.response.QuestionTailorAcceptedResponse;
import repit.repit_api_server.global.common.ApiResponse;

import java.util.Map;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class AiServerClient {

    public static final String SERVER_NAME = "AI";

    private final AiServerApi aiServerApi;
    private final ExternalApiExecutor executor;

    public MetaDataResponse sendMetaData(String authorization, MetaDataRequest request) {
        return executor.execute(SERVER_NAME,
                () -> aiServerApi.createMetaData(authorization, request),
                this::resolveMessage, false);
    }

    public QuestionResponse createQuestion() {
        ApiResponse<QuestionResponse> response = executor.execute(SERVER_NAME,
                aiServerApi::createQuestion,
                this::resolveMessage, true);

        return response == null ? null : response.getData();
    }

    public GenerateResponse generateMock(GenerateRequest request) {
        return executor.execute(SERVER_NAME,
                () -> aiServerApi.generateMock(request),
                this::resolveMessage, false);
    }

    public GenerateResponse requestProfile(ProfileRequest request) {
        return executor.execute(SERVER_NAME,
                () -> aiServerApi.requestProfile(request),
                this::resolveMessage, false);
    }

    public GenerateResponse requestQuestionCycle(QuestionCycleRequest request) {
        return executor.execute(SERVER_NAME,
                () -> aiServerApi.requestQuestionCycle(request),
                this::resolveMessage, false);
    }

    public FeedbackAcceptedResponse requestSoloFeedback(FeedbackSoloRequest request) {
        return executor.execute(SERVER_NAME,
                () -> aiServerApi.requestSoloFeedback(request),
                this::resolveMessage, false);
    }

    public FeedbackAcceptedResponse requestMultiFeedback(FeedbackMultiRequest request) {
        return executor.execute(SERVER_NAME,
                () -> aiServerApi.requestMultiFeedback(request),
                this::resolveMessage, false);
    }

    public AudioAnalysisAcceptedResponse requestAudioAnalysis(AudioAnalysisRequest request) {
        return executor.execute(SERVER_NAME,
                () -> aiServerApi.requestAudioAnalysis(request),
                this::resolveMessage, false);
    }

    public AudioAnalysisJobResponse getAudioAnalysisJob(String jobId) {
        return executor.execute(SERVER_NAME,
                () -> aiServerApi.getAudioAnalysisJob(jobId),
                this::resolveMessage, false);
    }

    public VideoAnalysisAcceptedResponse requestVideoAnalysis(Map<String, Object> request) {
        return executor.execute(SERVER_NAME,
                () -> aiServerApi.requestVideoAnalysis(request),
                this::resolveMessage, false);
    }

    public VideoAnalysisJobResponse getVideoAnalysisJob(String jobId) {
        return executor.execute(SERVER_NAME,
                () -> aiServerApi.getVideoAnalysisJob(jobId),
                this::resolveMessage, true);
    }

    public QuestionTailorAcceptedResponse tailorQuestions(QuestionTailorRequest request) {
//
        return executor.execute(SERVER_NAME,
                () -> aiServerApi.tailorQuestions(request),
                this::resolveMessage, false);
    }

    public QuestionTailorAcceptedResponse tailorQuestionsMulti(QuestionTailorMultiRequest request) {
        return executor.execute(SERVER_NAME,
                () -> aiServerApi.tailorQuestionsMulti(request),
                this::resolveMessage, false);
    }

    private String resolveMessage(HttpStatusCode status) {
        if (status.value() == 401) {
            return "AI 서버 인증에 실패했습니다.";
        }
        if (status.value() == 422) {
            return "AI 서버가 처리할 수 있는 형식의 데이터가 아닙니다.";
        }
        if (status.value() == 404) {
            return "요청한 AI 리소스를 찾을 수 없습니다.";
        }
        if (status.is5xxServerError()) {
            return "AI 응답 생성 중 오류가 발생했습니다.";
        }
        return "AI 서버 요청이 올바르지 않습니다. (" + status.value() + ")";
    }
}
