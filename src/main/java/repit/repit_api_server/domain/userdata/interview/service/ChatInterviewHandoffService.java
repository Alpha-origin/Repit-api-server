package repit.repit_api_server.domain.userdata.interview.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
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

    private final InterviewRepository interviewRepository;
    private final PersonaRepository personaRepository;
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
                .jobId(tailor.getAnalysisJobId())
                .persona(toPersona(persona))
                .tailored(Boolean.TRUE.equals(tailor.getTailored()))
                .questions(toQuestions(tailor))
                .build());
    }

    private ChatInterviewPrepareRequest.Persona toPersona(PersonaEntity persona) {
        return ChatInterviewPrepareRequest.Persona.builder()
                .personaId(persona.getPersonaId())
                .personaName(persona.getPersonaName())
                .major(persona.getMajor())
                .type(persona.getType())
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
