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
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

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

        List<PersonaEntity> personas = interviewers(interview);
        // 대표 면접관. 1:1은 한 명뿐이고, N:1은 진행 순서 맨 앞이다.
        PersonaEntity persona = personas.getFirst();

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
                .questions(toQuestions(tailor, defaultPersonaId(interview, persona),
                        personas.stream().map(PersonaEntity::getPersonaId).collect(Collectors.toSet())))
                .personas(personas.stream().map(this::toPersona).toList())
                .build());
    }

    /**
     * 면접관 전체. 1:1은 한 명, N:1은 진행 순서대로다.
     *
     * <p>맨 앞이 최상위 면접관 설정을 대표한다. 채팅 서버는 성향·어조·전공·난이도를 면접 하나에
     * 한 벌만 받던 시절의 계약을 아직 읽으므로 그 값은 그대로 둔다. N:1의 맨 앞은 생성할 때부터
     * 기술 면접관으로 고정돼 있어 전공이 반드시 있고, 면접도 그 면접관으로 시작한다 —
     * {@code InterviewService.orderForMulti} 참고.
     */
    private List<PersonaEntity> interviewers(InterviewEntity interview) {
        if (interview.getMode() != InterviewMode.MULTI) {
            if (interview.getPersonaId() == null) {
                throw BusinessException.unprocessable("면접에 면접관이 지정되어 있지 않습니다.");
            }
            return List.of(personaRepository.findById(interview.getPersonaId())
                    .orElseThrow(() -> BusinessException.notFound("페르소나가 없습니다")));
        }

        List<Long> personaIds = interviewPersonaRepository
                .findAllByInterviewIdOrderByPersonaOrderAsc(interview.getInterviewId()).stream()
                .map(InterviewPersonaEntity::getPersonaId)
                .toList();
        if (personaIds.isEmpty()) {
            throw BusinessException.unprocessable("면접에 면접관이 지정되어 있지 않습니다.");
        }
        // findAllById는 순서를 지키지 않는다. 진행 순서는 면접관 목록 쪽을 따른다.
        Map<Long, PersonaEntity> byId = personaRepository.findAllById(personaIds).stream()
                .collect(Collectors.toMap(PersonaEntity::getPersonaId, Function.identity()));
        if (!byId.keySet().containsAll(personaIds)) {
            throw BusinessException.unprocessable("면접관 정보가 일치하지 않습니다.");
        }
        return personaIds.stream().map(byId::get).toList();
    }

    private ChatInterviewPrepareRequest.Persona toPersona(PersonaEntity persona) {
        return ChatInterviewPrepareRequest.Persona.builder()
                .personaId(persona.getPersonaId())
                .personaName(persona.getPersonaName())
                .role(persona.getRole())
                // 대표 설정과 달리 대체값을 채우지 않는다. 비기술 면접관은 비어 있는 것이 맞다.
                .major(persona.getMajor())
                .type(persona.getType())
                .tone(persona.getTone())
                .level(persona.getLevel())
                .career(persona.getCareer())
                .gender(persona.getGender())
                .voiceIndex(persona.getVoiceIndex())
                .build();
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
     * <p>채점 기준(intention)과 기대 답변, 근거도 함께 넘긴다. 채팅 서버는 이것들을 손대지 않고 들고
     * 있다가 면접 기록과 함께 돌려주고, 그것이 그대로 채점 기준이 된다. 여기서 비워 보내면 되찾을 길이 없다.
     *
     * <p>id와 본문, 면접관이 비면 채팅 서버가 질문을 다룰 수 없으므로 넘기기 전에 멈춘다. 면접관이
     * personas에 없을 때도 채팅 서버가 같은 조건으로 반려하므로 여기서 먼저 막아 원인을 남긴다.
     */
    private List<ChatInterviewPrepareRequest.Question> toQuestions(
            QuestionTailorEntity tailor, Long defaultPersonaId, Set<Long> interviewerIds) {
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
                    if (!interviewerIds.contains(personaId)) {
                        throw BusinessException.unprocessable(
                                "질문의 면접관이 이 면접에 없습니다. id=" + question.getId() + ", personaId=" + personaId);
                    }
                    return ChatInterviewPrepareRequest.Question.builder()
                            .id(question.getId().longValue())
                            .category(question.getCategory())
                            .question(question.getQuestion())
                            .intention(question.getIntention())
                            .spokenText(question.getSpokenText())
                            .expectedAnswer(question.getExpectedAnswer())
                            .basedOn(question.getBasedOn())
                            .personaId(personaId)
                            .build();
                })
                .toList();
    }
}
