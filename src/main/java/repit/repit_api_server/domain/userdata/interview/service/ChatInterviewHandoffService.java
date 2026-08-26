package repit.repit_api_server.domain.userdata.interview.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import repit.repit_api_server.domain.userdata.interview.dto.request.ChatInterviewPrepareRequest;
import repit.repit_api_server.domain.userdata.interview.dto.response.ChatInterviewResponse;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
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
    private final ChatServerClient chatServerClient;

    public ChatInterviewResponse deliver(QuestionTailorEntity tailor) {
        InterviewEntity interview = interviewRepository.findById(tailor.getInterviewId())
                .orElseThrow(() -> BusinessException.notFound("면접을 찾을 수 없습니다"));

        return chatServerClient.prepareInterview(ChatInterviewPrepareRequest.builder()
                .sessionId(interview.getSessionId())
                .interviewId(interview.getInterviewId())
                .userId(interview.getUserId())
                .status(interview.getStatus())
                .questions(toQuestions(tailor, personaIdOf(interview)))
                .build());
    }

    /**
     * 질문을 던질 면접관. 채팅 서버는 질문마다 이 값을 필수로 받는다.
     *
     * <p>N:1 면접은 면접관이 여럿이라 {@code interview.persona_id}가 비어 있다. 그대로 넘기면
     * 채팅 서버가 본문을 통째로 반려하고, 그 실패는 여기 로그에 이유 없이 남는다. 어느 면접관의
     * 질문인지 가릴 수 있게 되기 전까지는 넘기기 전에 막는다.
     */
    private Long personaIdOf(InterviewEntity interview) {
        Long personaId = interview.getPersonaId();
        if (personaId == null) {
            throw BusinessException.unprocessable("면접에 면접관이 지정되어 있지 않습니다.");
        }
        return personaId;
    }

    /**
     * 면접에 실제로 쓸 질문만 넘긴다. 재작성이 실패한 건은 폴백으로 원질문이 들어와 있어
     * 어느 쪽이든 같은 형태로 나간다.
     *
     * <p>id와 본문은 채팅 서버의 필수값이라, 하나라도 비면 반려당하기 전에 여기서 멈춘다.
     */
    private List<ChatInterviewPrepareRequest.Question> toQuestions(QuestionTailorEntity tailor, Long personaId) {
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
                            .id(question.getId().longValue())
                            .category(question.getCategory())
                            .question(question.getQuestion())
                            .personaId(personaId)
                            .build();
                })
                .toList();
    }
}
