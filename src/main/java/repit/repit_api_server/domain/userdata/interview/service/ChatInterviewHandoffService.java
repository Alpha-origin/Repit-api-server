package repit.repit_api_server.domain.userdata.interview.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import repit.repit_api_server.domain.metadata.entity.AnalysisDataEntity;
import repit.repit_api_server.domain.metadata.repository.AnalysisDataRepository;
import repit.repit_api_server.domain.userdata.interview.dto.request.ChatInterviewPrepareRequest;
import repit.repit_api_server.domain.userdata.interview.dto.response.ChatInterviewResponse;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.persona.entity.PersonaEntity;
import repit.repit_api_server.domain.userdata.persona.repository.PersonaRepository;
import repit.repit_api_server.domain.userdata.question.dto.response.TailoredQuestionResponse;
import repit.repit_api_server.domain.userdata.question.entity.QuestionTailorEntity;
import repit.repit_api_server.global.client.ChatServerClient;
import repit.repit_api_server.global.exception.BusinessException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 질문 재작성이 끝난 뒤, DB에 모인 면접 데이터를 채팅 서버로 넘긴다.
 *
 * <p>분석 서버 콜백에서 호출되므로 사용자 토큰이 없다. 사용자 인증은 면접 시작 요청에서 이미 끝났고,
 * 여기서는 서버 간 호출로 userId를 본문에 실어 보낸다.
 */
@Service
@RequiredArgsConstructor
public class ChatInterviewHandoffService {

    private static final Logger log = LoggerFactory.getLogger(ChatInterviewHandoffService.class);

    private final InterviewRepository interviewRepository;
    private final PersonaRepository personaRepository;
    private final AnalysisDataRepository analysisDataRepository;
    private final ChatServerClient chatServerClient;

    public ChatInterviewResponse deliver(QuestionTailorEntity tailor) {
        InterviewEntity interview = interviewRepository.findById(tailor.getInterviewId())
                .orElseThrow(() -> BusinessException.notFound("면접을 찾을 수 없습니다"));
        PersonaEntity persona = personaRepository.findById(interview.getPersonaId())
                .orElseThrow(() -> BusinessException.notFound("페르소나가 없습니다"));

        return chatServerClient.prepareInterview(ChatInterviewPrepareRequest.builder()
                .sessionId(interview.getSessionId())
                .interviewId(interview.getInterviewId())
                .userId(interview.getUserId())
                .status(interview.getStatus())
                .jobId(resolveAnalysisJobId(tailor))
                .persona(toPersona(persona))
                .tailored(Boolean.TRUE.equals(tailor.getTailored()))
                .questions(toQuestions(tailor))
                .build());
    }

    /**
     * 채팅 서버가 분석 결과를 되찾는 유일한 키다. 이 값이 비면 채팅 서버의 결과 조회가 통째로
     * 빈손(result = null)이 되므로, 넘기기 전에 반드시 채워둔다.
     *
     * <p>analysis_job_id 컬럼이 생기기 전에 만들어진 재작성 건에는 이 값이 없다. 그런 건도
     * 면접 시작을 다시 누르면 그대로 채팅 서버로 넘어가므로, 사용자의 가장 최근 완료 분석으로
     * 메워 넣는다. 원질문 자체가 그 분석에서 나왔으니 같은 작업을 가리킨다.
     *
     * <p>메울 것조차 없으면 빈 jobId로 넘기지 않고 전달을 멈춘다. 넘겨봐야 채팅 서버가
     * 결과를 못 찾고, 그 실패는 이쪽 로그에 남지 않아 원인을 좇을 수 없다.
     */
    private String resolveAnalysisJobId(QuestionTailorEntity tailor) {
        String analysisJobId = tailor.getAnalysisJobId();
        if (analysisJobId != null && !analysisJobId.isBlank()) {
            return analysisJobId;
        }

        String recovered = analysisDataRepository
                .findTopByUserIdAndResultIsNotNullOrderByCreatedAtDesc(tailor.getUserId())
                .map(AnalysisDataEntity::getJobId)
                .orElseThrow(() -> BusinessException.unprocessable(
                        "채팅 서버에 넘길 분석 작업을 찾을 수 없습니다. 포트폴리오 분석을 먼저 진행해주세요."));

        log.warn("재작성 건에 분석 작업 id가 없어 최근 분석으로 채웁니다. tailorId={}, interviewId={}, jobId={}",
                tailor.getTailorId(), tailor.getInterviewId(), recovered);
        // 호출자가 전달 결과와 함께 저장한다. 다음 전달부터는 이 값을 그대로 쓴다.
        tailor.setAnalysisJobId(recovered);
        return recovered;
    }

    private ChatInterviewPrepareRequest.Persona toPersona(PersonaEntity persona) {
        return ChatInterviewPrepareRequest.Persona.builder()
                .personaId(persona.getPersonaId())
                .personaName(persona.getPersonaName())
                .major(persona.getMajor())
                .type(persona.getType())
                .level(persona.getLevel())
                .career(persona.getCareer())
                .gender(persona.getGender())
                .build();
    }

    /**
     * 최종 질문에 원질문 본문을 짝지어 넘긴다.
     * 폴백이면 두 값이 같지만, 채팅 서버가 분기하지 않도록 형태는 항상 같게 유지한다.
     */
    private List<ChatInterviewPrepareRequest.Question> toQuestions(QuestionTailorEntity tailor) {
        List<TailoredQuestionResponse> finalQuestions = tailor.getQuestions() == null
                ? tailor.getSourceQuestions()
                : tailor.getQuestions();
        if (finalQuestions == null || finalQuestions.isEmpty()) {
            throw BusinessException.unprocessable("채팅 서버에 넘길 질문이 없습니다.");
        }

        Map<Integer, String> originals = new HashMap<>();
        if (tailor.getSourceQuestions() != null) {
            for (TailoredQuestionResponse question : tailor.getSourceQuestions()) {
                originals.put(question.getId(), question.getQuestion());
            }
        }

        return finalQuestions.stream()
                .map(question -> ChatInterviewPrepareRequest.Question.builder()
                        .id(question.getId())
                        .category(question.getCategory())
                        .question(question.getQuestion())
                        .originalQuestion(originals.getOrDefault(question.getId(), question.getQuestion()))
                        .expectedAnswer(question.getExpectedAnswer())
                        .basedOn(question.getBasedOn())
                        .build())
                .toList();
    }
}
