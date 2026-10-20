package repit.repit_api_server.domain.userdata.interview.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import repit.repit_api_server.domain.metadata.support.JobRole;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewPersonaEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewPersonaRepository;
import repit.repit_api_server.domain.userdata.persona.entity.PersonaEntity;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Major;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Role;
import repit.repit_api_server.domain.userdata.persona.repository.PersonaRepository;
import repit.repit_api_server.domain.userdata.persona.support.UserJobMajors;
import repit.repit_api_server.domain.userdata.question.entity.QuestionTailorEntity;
import repit.repit_api_server.domain.userdata.question.preparation.FailureStage;
import repit.repit_api_server.domain.userdata.question.preparation.PreparationState;
import repit.repit_api_server.domain.userdata.question.preparation.PreparationStatus;
import repit.repit_api_server.domain.userdata.question.repository.QuestionTailorRepository;
import repit.repit_api_server.global.auth.AuthUser;
import repit.repit_api_server.global.exception.BusinessException;

/** 첫 준비 접수의 일반 직무를 면접 전용 면접관으로 보존한다(G-A31). */
@Service
@RequiredArgsConstructor
public class InterviewJobSnapshotService {
    private final InterviewRepository interviews;
    private final InterviewPersonaRepository members;
    private final PersonaRepository personas;
    private final QuestionTailorRepository tailors;
    private final jakarta.persistence.EntityManager entityManager;

    @Transactional
    public InterviewEntity capture(AuthUser user, Long interviewId) {
        return snapshot(user, interviewId, false);
    }

    /**
     * 준비 재시도 때의 스냅샷. 질문을 처음부터 다시 만드는 재시도이고 아직 면접 전용 면접관이 없으면 지금 만든다.
     * 첫 준비 때 스냅샷 없이 질문 생성이 실패했다면(직무를 일반 직군으로 바꾸기 전에 준비한 면접 등) 재시도가
     * 사용자의 지금 직무로 질문을 다시 만드므로, 면접관 직무·직무명도 그 값으로 고정해야 서로 어긋나지 않는다.
     * 채팅 서버 전달만 실패한 경우는 질문이 이미 정해져 있어 건드리지 않는다.
     */
    @Transactional
    public InterviewEntity captureForRetry(AuthUser user, Long interviewId) {
        return snapshot(user, interviewId, true);
    }

    private InterviewEntity snapshot(AuthUser user, Long interviewId, boolean retry) {
        InterviewEntity interview = interviews.findForJobSnapshot(interviewId)
                .orElseThrow(() -> BusinessException.notFound("면접을 찾을 수 없습니다"));
        // OSIV가 앞선 조회의 managed 엔티티를 보관해도 잠금 획득 뒤 최신 FK를 다시 읽는다.
        entityManager.refresh(interview, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (!user.id().equals(interview.getUserId())) {
            throw BusinessException.forbidden("본인의 면접만 시작할 수 있습니다.");
        }
        QuestionTailorEntity latest = tailors.findTopByInterviewIdOrderByCreatedAtDesc(interviewId).orElse(null);
        if (interview.getStatus() != Status.IN_PROGRESS || (latest != null && !(retry && regeneratesQuestions(latest)))) {
            return interview;
        }
        InterviewPersonaEntity link = interview.getMode() == InterviewMode.MULTI
                ? members.findAllByInterviewIdOrderByPersonaOrderAsc(interviewId).stream().findFirst().orElse(null) : null;
        Long personaId = link == null ? interview.getPersonaId() : link.getPersonaId();
        PersonaEntity source = personas.findById(personaId)
                .orElseThrow(() -> BusinessException.notFound("페르소나를 찾을 수 없습니다"));
        if (source.isInterviewSnapshot()) {
            return interview;
        }
        JobRole job = JobRole.find(UserJobMajors.normalize(user.user().getMajor())).orElse(JobRole.BACKEND);
        if (!job.requiresJobTitle()) {
            return interview;
        }
        String title = user.user().getJobTitle();
        if (title == null || title.isBlank() || title.strip().length() > 30 || title.matches("(?s).*\\p{Cntrl}.*")) {
            throw BusinessException.unprocessable("일반 직군의 직무명을 먼저 저장해 주세요.");
        }
        if (source.getRole() != Role.TECH) {
            throw BusinessException.unprocessable("일반 직군에는 전문 면접관이 필요합니다.");
        }
        PersonaEntity snapshot = personas.save(source.toBuilder().personaId(null)
                .personaName("__interview_snapshot_" + interviewId).snapshotSourceName(source.getPersonaName())
                .major(Major.valueOf(job.name())).jobTitle(title.strip()).interviewSnapshot(true).build());
        if (link == null) {
            interview.setPersonaId(snapshot.getPersonaId());
            interviews.save(interview);
        } else {
            link.setPersonaId(snapshot.getPersonaId());
            members.save(link);
        }
        return interview;
    }

    private static boolean regeneratesQuestions(QuestionTailorEntity tailor) {
        PreparationState state = PreparationState.of(tailor);
        return state.status() == PreparationStatus.FAILED && state.failureStage() == FailureStage.QUESTION_GENERATION;
    }
}
