package repit.repit_api_server.domain.userdata.interview.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import repit.repit_api_server.domain.userdata.interview.dto.request.ChatInterviewPrepareRequest;
import repit.repit_api_server.domain.userdata.interview.dto.response.ChatInterviewResponse;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewPersonaEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewPersonaRepository;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.persona.entity.PersonaEntity;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Major;
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

    /**
     * 전공이 없는 면접관을 넘길 때 대신 채우는 값.
     *
     * <p>인사·CEO 면접관에게는 전공이 없지만 채팅 서버는 이 값을 필수로 받아, 비워 보내면
     * 면접이 아예 열리지 않는다. 면접을 못 여는 것보다 낫다고 보고 하나를 채워 보낸다.
     * 채팅 서버가 전공 없는 면접관을 받게 되면 이 대체는 걷어낸다.
     */
    private static final Major FALLBACK_MAJOR = Major.BACKEND;

    private final InterviewRepository interviewRepository;
    private final PersonaRepository personaRepository;
    private final InterviewPersonaRepository interviewPersonaRepository;
    private final ChatServerClient chatServerClient;

    public ChatInterviewResponse deliver(QuestionTailorEntity tailor) {
        InterviewEntity interview = interviewRepository.findById(tailor.getInterviewId())
                .orElseThrow(() -> BusinessException.notFound("면접을 찾을 수 없습니다"));

        PersonaEntity persona = representativePersona(interview);

        return chatServerClient.prepareInterview(ChatInterviewPrepareRequest.builder()
                .sessionId(interview.getSessionId())
                .interviewId(interview.getInterviewId())
                .userId(interview.getUserId())
                .status(interview.getStatus())
                // 면접 방식. DB에서 NOT NULL로 SOLO/MULTI 중 하나가 이미 정해져 있어 그대로 싣는다.
                .mode(interview.getMode())
                // 면접관 설정 네 가지. 하나라도 비면 채팅 서버가 본문을 통째로 반려한다.
                .personality(persona.getType())
                .tone(persona.getTone())
                .major(persona.getMajor() == null ? FALLBACK_MAJOR : persona.getMajor())
                .level(persona.getLevel())
                .questions(toQuestions(tailor, defaultPersonaId(interview, persona)))
                .build());
    }

    /**
     * 면접관 설정을 대표할 면접관 한 명.
     *
     * <p>채팅 서버는 성향·어조·전공·난이도를 면접 하나에 한 벌만 받는다. 1:1은 면접관이
     * 한 명뿐이라 그대로지만, N:1은 여럿 중 하나를 골라야 한다.
     *
     * <p>N:1은 진행 순서 맨 앞을 쓴다. 그 자리는 생성할 때부터 기술 면접관으로 고정돼 있어
     * 전공이 반드시 있고, 면접도 그 면접관으로 시작한다 — {@code InterviewService.orderForMulti} 참고.
     * 나머지 면접관의 성향과 어조는 이 계약에서 표현되지 않는다. 질문별로 나누려면 채팅 서버가
     * 질문마다 설정을 받도록 먼저 넓혀야 한다.
     */
    private PersonaEntity representativePersona(InterviewEntity interview) {
        Long personaId = interview.getMode() == InterviewMode.MULTI
                ? firstMemberPersonaId(interview.getInterviewId())
                : interview.getPersonaId();
        if (personaId == null) {
            throw BusinessException.unprocessable("면접에 면접관이 지정되어 있지 않습니다.");
        }
        return personaRepository.findById(personaId)
                .orElseThrow(() -> BusinessException.notFound("페르소나가 없습니다"));
    }

    /** N:1 면접의 진행 순서 첫 면접관. 생성 시점에 반드시 한 명 이상 저장돼 있다. */
    private Long firstMemberPersonaId(Long interviewId) {
        return interviewPersonaRepository.findAllByInterviewIdOrderByPersonaOrderAsc(interviewId).stream()
                .findFirst()
                .map(InterviewPersonaEntity::getPersonaId)
                .orElse(null);
    }

    /**
     * 질문에 면접관이 붙어 있지 않을 때 쓸 면접관.
     *
     * <p>1:1 면접은 질문마다 면접관을 나눌 일이 없어 전부 이 한 명이다. N:1 질문에는 분석 서버가
     * 면접관을 달아 보내주므로 여기서 대신 채우지 않는다. 대표 면접관으로 메우면 면접관이 빠진
     * 질문이 조용히 기술 면접관 것으로 묻혀, 프론트가 면접관 전환을 잘못 읽는다.
     */
    private Long defaultPersonaId(InterviewEntity interview, PersonaEntity persona) {
        if (interview.getMode() == InterviewMode.MULTI) {
            return null;
        }
        return persona.getPersonaId();
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
