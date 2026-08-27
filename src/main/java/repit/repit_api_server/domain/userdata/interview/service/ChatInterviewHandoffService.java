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

        return chatServerClient.prepareInterview(ChatInterviewPrepareRequest.builder()
                .sessionId(interview.getSessionId())
                .interviewId(interview.getInterviewId())
                .userId(interview.getUserId())
                .status(interview.getStatus())
                .questions(toQuestions(tailor, defaultPersonaId(interview)))
                .build());
    }

    /**
     * 질문에 면접관이 붙어 있지 않을 때 쓸 면접관.
     *
     * <p>1:1 면접은 질문마다 면접관을 나눌 일이 없어 전부 이 한 명이다. N:1 질문에는 분석 서버가
     * 면접관을 달아 보내주므로 이 값이 쓰이지 않고, 실제로 {@code interview.persona_id}도 비어 있다.
     */
    private Long defaultPersonaId(InterviewEntity interview) {
        if (interview.getPersonaId() == null) {
            return null;
        }
        return personaRepository.findById(interview.getPersonaId())
                .orElseThrow(() -> BusinessException.notFound("페르소나가 없습니다"))
                .getPersonaId();
    }

    /**
     * 면접에 실제로 쓸 질문만 넘긴다. 재작성이 실패한 건은 폴백으로 원질문이 들어와 있어
     * 어느 쪽이든 같은 형태로 나간다.
     *
     * <p>채팅 서버는 질문마다 면접관을 달아 두고, 프론트는 그 값이 바뀌는 것으로 면접관 전환을
     * 감지한다. N:1은 질문에 붙어 온 면접관을 그대로 쓰고, 1:1은 면접관이 하나뿐이라 전부 같다.
     *
     * <p>기대 답변과 근거도 함께 넘긴다. 채팅 서버는 이 둘을 손대지 않고 들고 있다가 면접
     * 기록과 함께 돌려주고, 그것이 그대로 채점 기준이 된다. 여기서 비워 보내면 되찾을 길이 없다.
     *
     * <p>id와 본문, 면접관이 비면 채팅 서버가 질문을 다룰 수 없으므로 넘기기 전에 멈춘다.
     */
    private List<ChatInterviewPrepareRequest.Question> toQuestions(QuestionTailorEntity tailor, Long defaultPersonaId) {
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
                    Long personaId = question.getPersonaId() == null ? defaultPersonaId : question.getPersonaId();
                    if (personaId == null) {
                        throw BusinessException.unprocessable("질문에 면접관이 지정되어 있지 않습니다. id=" + question.getId());
                    }
                    return ChatInterviewPrepareRequest.Question.builder()
                            .id(question.getId().longValue())
                            .category(question.getCategory())
                            .question(question.getQuestion())
                            .expectedAnswer(question.getExpectedAnswer())
                            .basedOn(question.getBasedOn())
                            .personaId(personaId)
                            .build();
                })
                .toList();
    }
}
