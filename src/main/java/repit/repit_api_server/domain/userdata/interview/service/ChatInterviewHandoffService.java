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

import java.util.List;

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
        PersonaEntity persona = findPersona(interview);

        return chatServerClient.prepareInterview(ChatInterviewPrepareRequest.builder()
                .sessionId(interview.getSessionId())
                .interviewId(interview.getInterviewId())
                .userId(interview.getUserId())
                .personaId(persona.getPersonaId())
                .personaType(persona.getType())
                .questions(toQuestions(tailor))
                .build());
    }

    /**
     * 면접을 진행할 면접관.
     *
     * <p>N:1 면접은 면접관이 여럿이라 {@code interview.persona_id}가 비어 있다. 그대로 넘기면
     * 채팅 서버가 어느 면접관으로 면접을 열지 정하지 못한다. 어느 면접관의 질문인지 가릴 수
     * 있게 되기 전까지는 넘기기 전에 막는다.
     */
    private PersonaEntity findPersona(InterviewEntity interview) {
        if (interview.getPersonaId() == null) {
            throw BusinessException.unprocessable("면접에 면접관이 지정되어 있지 않습니다.");
        }
        return personaRepository.findById(interview.getPersonaId())
                .orElseThrow(() -> BusinessException.notFound("페르소나가 없습니다"));
    }

    /**
     * 면접에 실제로 쓸 질문만 넘긴다. 재작성이 실패한 건은 폴백으로 원질문이 들어와 있어
     * 어느 쪽이든 같은 형태로 나간다.
     *
     * <p>id와 본문이 비면 채팅 서버가 질문을 다룰 수 없으므로 넘기기 전에 멈춘다.
     */
    private List<ChatInterviewPrepareRequest.Question> toQuestions(QuestionTailorEntity tailor) {
        List<TailoredQuestionResponse> finalQuestions = tailor.getQuestions() == null
                ? tailor.getSourceQuestions()
                : tailor.getQuestions();
        if (finalQuestions == null || finalQuestions.isEmpty()) {
            throw BusinessException.unprocessable("채팅 서버에 넘길 질문이 없습니다.");
        }

        return finalQuestions.stream()
                .map(question -> {
                    if (question.getId() == null || question.getQuestion() == null || question.getQuestion().isBlank()) {
                        throw BusinessException.unprocessable("채팅 서버에 넘길 수 없는 질문이 있습니다. id=" + question.getId());
                    }
                    return ChatInterviewPrepareRequest.Question.builder()
                            .questionId(question.getId().longValue())
                            .intention(intentionOf(question))
                            .content(question.getQuestion())
                            .build();
                })
                .toList();
    }

    /**
     * 이 질문으로 무엇을 확인하려는지.
     *
     * <p>분석 서버는 의도를 따로 주지 않는다. 대신 질문마다 붙는 기대 답변이 곧 확인하려는
     * 것이라 그것을 쓴다.
     *
     * <p>기대 답변이 비어 오면 분류라도 넘긴다. 이 값은 면접이 끝나고 피드백을 요청할 때
     * 채팅 서버를 거쳐 분석 서버로 되돌아가므로, 비워 보내면 그 단계에서 되찾을 길이 없다.
     */
    private String intentionOf(TailoredQuestionResponse question) {
        String expectedAnswer = question.getExpectedAnswer();
        if (expectedAnswer != null && !expectedAnswer.isBlank()) {
            return expectedAnswer;
        }
        return question.getCategory();
    }
}
