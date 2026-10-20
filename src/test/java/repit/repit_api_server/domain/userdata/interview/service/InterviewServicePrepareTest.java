package repit.repit_api_server.domain.userdata.interview.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import repit.repit_api_server.domain.company.dto.CompanyBadge;
import repit.repit_api_server.domain.company.dto.CompanySnapshot;
import repit.repit_api_server.domain.company.service.CompanySnapshotFactory;
import repit.repit_api_server.domain.metadata.service.AnalysisLaunchService;
import repit.repit_api_server.domain.userdata.answer.repository.AnswerRepository;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewPersonaRepository;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.persona.repository.PersonaRepository;
import repit.repit_api_server.domain.userdata.question.entity.QuestionTailorEntity;
import repit.repit_api_server.domain.userdata.question.entity.enums.TailorStatus;
import repit.repit_api_server.domain.userdata.question.repository.QuestionRepository;
import repit.repit_api_server.domain.userdata.question.service.QuestionTailorService;
import repit.repit_api_server.global.auth.AuthUser;
import repit.repit_api_server.global.client.ChatServerClient;
import repit.repit_api_server.global.exception.BusinessException;
import repit.repit_api_server.global.response.UserResponse;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 비개발 직무는 지금 직무에 맞는 분석을 아직 만들 수 없다(분석 서버 새 요청 모양 C-3 전).
 * 면접 준비를 막지 않으면 개발 직무였을 때 남은 분석으로 개발 질문을 준비한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InterviewServicePrepareTest {

    private static final Long USER_ID = 7L;
    private static final Long INTERVIEW_ID = 30L;

    @Mock
    private InterviewRepository interviewRepository;
    @Mock
    private QuestionRepository questionRepository;
    @Mock
    private ChatServerClient chatServerClient;
    @Mock
    private AnswerRepository answerRepository;
    @Mock
    private PersonaRepository personaRepository;
    @Mock
    private QuestionTailorService questionTailorService;
    @Mock
    private InterviewPersonaRepository interviewPersonaRepository;
    @Mock
    private AnalysisLaunchService analysisLaunchService;
    @Mock
    private CompanySnapshotFactory companySnapshotFactory;

    @Mock private InterviewJobSnapshotService snapshotService;

    private InterviewService service;

    @BeforeEach
    void setUp() {
        service = new InterviewService(interviewRepository, questionRepository, chatServerClient,
                answerRepository, personaRepository, questionTailorService,
                interviewPersonaRepository, analysisLaunchService, companySnapshotFactory, snapshotService);
        when(interviewRepository.findById(INTERVIEW_ID)).thenReturn(Optional.of(InterviewEntity.builder()
                .interviewId(INTERVIEW_ID).userId(USER_ID).sessionId("session-1").build()));
    }

    private static AuthUser userWithMajor(String major) {
        UserResponse user = new UserResponse();
        ReflectionTestUtils.setField(user, "id", USER_ID);
        ReflectionTestUtils.setField(user, "major", major);
        return new AuthUser(user, "Bearer token");
    }

    @Test
    void 모든_직군과_직무를_고른_적_없는_사용자는_면접을_준비한다() {
        when(questionTailorService.requestTailor(any(), any())).thenReturn(QuestionTailorEntity.builder()
                .interviewId(INTERVIEW_ID).userId(USER_ID).status(TailorStatus.WAITING).build());

        when(snapshotService.capture(any(), any())).thenAnswer(inv -> interviewRepository.findById(INTERVIEW_ID).orElseThrow());
        for (String major : new String[]{"MAJOR_FRONTEND", "MAJOR_PRODUCT_MANAGER", "MAJOR_GEN_MARKETING", null}) {
            AuthUser user = userWithMajor(major);
            service.prepareInterview(user, INTERVIEW_ID);

            verify(analysisLaunchService).launchIfMissing(user);
            verify(questionTailorService).requestTailor(any(), any());
            clearInvocations(analysisLaunchService, questionTailorService);
        }
    }

    /** 준비 응답의 기업 배지는 면접 스냅샷에서 읽는다. 기업을 고르지 않은 면접은 비어 있다. */
    @Test
    void 준비_응답과_재시도_응답에_면접의_기업_배지를_싣는다() {
        when(interviewRepository.findById(INTERVIEW_ID)).thenReturn(Optional.of(InterviewEntity.builder()
                .interviewId(INTERVIEW_ID).userId(USER_ID).sessionId("session-1").companyId(12L)
                .companySnapshot(new CompanySnapshot("12", "샘플커머스", "CONSUMER", List.of(), "요약"))
                .build()));
        QuestionTailorEntity tailor = QuestionTailorEntity.builder()
                .interviewId(INTERVIEW_ID).userId(USER_ID).status(TailorStatus.PENDING).build();
        when(questionTailorService.requestTailor(any(), any())).thenReturn(tailor);
        when(questionTailorService.retryPreparation(any(), any())).thenReturn(tailor);
        AuthUser user = userWithMajor("MAJOR_FRONTEND");

        assertThat(service.prepareInterview(user, INTERVIEW_ID).getCompany())
                .isEqualTo(new CompanyBadge(12L, "샘플커머스"));
        assertThat(service.retryPreparation(user, INTERVIEW_ID).getCompany())
                .isEqualTo(new CompanyBadge(12L, "샘플커머스"));

        when(interviewRepository.findById(INTERVIEW_ID)).thenReturn(Optional.of(InterviewEntity.builder()
                .interviewId(INTERVIEW_ID).userId(USER_ID).sessionId("session-1").build()));
        assertThat(service.prepareInterview(user, INTERVIEW_ID).getCompany()).isNull();
    }

    @Test
    void 일반_직군의_준비_재시도는_직무_고정을_거친_면접으로_다시_준비한다() {
        InterviewEntity snapshotted = InterviewEntity.builder().interviewId(INTERVIEW_ID).userId(USER_ID)
                .sessionId("session-1").personaId(11L).build();
        AuthUser user = userWithMajor("MAJOR_GEN_MARKETING");
        when(snapshotService.captureForRetry(user, INTERVIEW_ID)).thenReturn(snapshotted);
        when(questionTailorService.retryPreparation(any(), any())).thenReturn(QuestionTailorEntity.builder()
                .interviewId(INTERVIEW_ID).userId(USER_ID).status(TailorStatus.PENDING).build());

        service.retryPreparation(user, INTERVIEW_ID);

        verify(snapshotService).captureForRetry(user, INTERVIEW_ID);
        verify(questionTailorService).retryPreparation(snapshotted, user.user());
    }

    @Test
    void 개발과_IT_비개발_직무의_준비_재시도는_직무_고정을_하지_않는다() {
        when(questionTailorService.retryPreparation(any(), any())).thenReturn(QuestionTailorEntity.builder()
                .interviewId(INTERVIEW_ID).userId(USER_ID).status(TailorStatus.PENDING).build());
        for (String major : new String[]{"MAJOR_BACKEND", "MAJOR_QA_ENGINEER", null}) {
            service.retryPreparation(userWithMajor(major), INTERVIEW_ID);
        }
        verify(snapshotService, never()).captureForRetry(any(), any());
    }
}
