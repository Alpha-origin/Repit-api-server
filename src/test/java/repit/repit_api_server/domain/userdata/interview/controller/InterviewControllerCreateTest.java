package repit.repit_api_server.domain.userdata.interview.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import repit.repit_api_server.domain.company.dto.CompanyBadge;
import repit.repit_api_server.domain.userdata.answer.service.AnswerService;
import repit.repit_api_server.domain.userdata.interview.dto.request.CreateInterviewRequest;
import repit.repit_api_server.domain.userdata.interview.dto.response.InterviewResponse;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewCreateRequestEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewCreateRequestRepository;
import repit.repit_api_server.domain.userdata.interview.service.ChatInterviewResultService;
import repit.repit_api_server.domain.userdata.interview.service.InterviewCreateService;
import repit.repit_api_server.domain.userdata.interview.service.InterviewService;
import repit.repit_api_server.global.auth.AuthUser;
import repit.repit_api_server.global.error.GlobalExceptionHandler;
import repit.repit_api_server.global.response.UserResponse;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 최종 면접 생성의 요청 형식(00 §3-2, §8). 헤더·ID 형식 오류는 422 INVALID_INTERVIEW_REQUEST이고, 본문에 실려 온 기업 정보는
 * 쓰지 않는다. 키 저장소의 유일 제약·트랜잭션은 실제 DB 테스트(InterviewCreateIdempotencyTest)에서 본다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InterviewControllerCreateTest {

    private static final Long USER_ID = 7L;
    private static final String KEY = "3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b";

    @Mock
    private InterviewService interviewService;
    @Mock
    private InterviewCreateRequestRepository interviewCreateRequestRepository;
    @Mock
    private PlatformTransactionManager transactionManager;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        InterviewCreateService createService = new InterviewCreateService(interviewService,
                interviewCreateRequestRepository, new TransactionTemplate(transactionManager));
        mockMvc = MockMvcBuilders.standaloneSetup(new InterviewController(interviewService, createService,
                        (ChatInterviewResultService) null, (AnswerService) null))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        UserResponse user = new UserResponse();
        ReflectionTestUtils.setField(user, "id", USER_ID);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AuthUser(user, "Bearer token"), null, List.of()));

        when(interviewService.createInterview(eq(USER_ID), any())).thenReturn(response(41L));
        when(interviewService.getInterviewById(USER_ID, 41L)).thenReturn(response(41L));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static InterviewResponse response(Long interviewId) {
        return new InterviewResponse(interviewId, USER_ID, InterviewMode.SOLO, 1L, List.of(), null, "session",
                Status.IN_PROGRESS, LocalDateTime.now(), new CompanyBadge(12L, "샘플커머스"), null);
    }

    @Test
    void 답변방식_enum을_받고_잘못된_값은_거절한다() throws Exception {
        for (String mode : List.of("TEXT", "VOICE")) {
            mockMvc.perform(create("{\"personaId\":1,\"answerMode\":\"" + mode + "\"}"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(create("{\"personaId\":1,\"answerMode\":null}"))
                .andExpect(status().isOk());
        mockMvc.perform(create("{\"personaId\":1,\"answerMode\":\"INVALID\"}"))
                .andExpect(status().isBadRequest());

    }

    private static MockHttpServletRequestBuilder create(String body) {
        return post("/api/interviews/create").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void 키_없는_요청은_예전처럼_만든다() throws Exception {
        mockMvc.perform(create("{\"personaId\": 1, \"companyId\": 12}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.interviewId").value(41))
                .andExpect(jsonPath("$.data.company.companyId").value(12))
                .andExpect(jsonPath("$.data.company.displayName").value("샘플커머스"));

        verify(interviewCreateRequestRepository, never()).findByUserIdAndIdempotencyKey(any(), any());
    }

    @Test
    void UUID가_아닌_키는_422다() throws Exception {
        for (String key : List.of("not-a-uuid", "", "1-1-1-1-1", KEY + "0")) {
            mockMvc.perform(create("{\"personaId\": 1}").header("Idempotency-Key", key))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("INVALID_INTERVIEW_REQUEST"))
                    .andExpect(jsonPath("$.message").exists());
        }
        verify(interviewService, never()).createInterview(any(), any());
    }

    /** 웹은 id를 JavaScript number로 다룬다. 0 이하나 안전한 정수 범위를 넘는 값은 받지 않는다. */
    @Test
    void 범위를_벗어난_companyId는_422다() throws Exception {
        for (String companyId : List.of("0", "-3", "9007199254740992")) {
            mockMvc.perform(create("{\"personaId\": 1, \"companyId\": " + companyId + "}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("INVALID_INTERVIEW_REQUEST"));
        }
        mockMvc.perform(create("{\"personaId\": 1, \"companyId\": 9007199254740991}"))
                .andExpect(status().isOk());
    }

    /** 브라우저가 보낸 기업 정보는 생성 근거가 아니다. 서버는 companyId만 받아 DB에서 다시 만든다. */
    @Test
    void 본문에_실려_온_기업_정보는_버린다() throws Exception {
        mockMvc.perform(create("""
                        {
                          "personaId": 1,
                          "companyId": 12,
                          "companySnapshot": { "displayName": "위조 기업" },
                          "facts": [{ "factId": "1", "kind": "TALENT", "title": "위조", "content": "위조" }],
                          "interviewerBrief": "위조 요약",
                          "review": { "reviewedBy": "위조" }
                        }"""))
                .andExpect(status().isOk());

        ArgumentCaptor<CreateInterviewRequest> sent = ArgumentCaptor.forClass(CreateInterviewRequest.class);
        verify(interviewService).createInterview(eq(USER_ID), sent.capture());
        assertThat(sent.getValue().getCompanyId()).isEqualTo(12L);
        assertThat(sent.getValue().getPersonaId()).isEqualTo(1L);
    }

    @Test
    void 같은_키에_다른_설정이면_409다() throws Exception {
        when(interviewCreateRequestRepository.findByUserIdAndIdempotencyKey(USER_ID, UUID.fromString(KEY)))
                .thenReturn(Optional.of(InterviewCreateRequestEntity.builder()
                        .userId(USER_ID).idempotencyKey(UUID.fromString(KEY)).requestHash("0".repeat(64))
                        .interviewId(41L).build()));

        mockMvc.perform(create("{\"personaId\": 1, \"companyId\": 12}").header("Idempotency-Key", KEY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        verify(interviewService, never()).createInterview(any(), any());
        verify(interviewService, never()).getInterviewById(any(), any());
    }

    /** 대문자로 보낸 키도 같은 키다. 처음 요청이 키를 저장하고, 다시 보낸 요청은 저장된 면접을 그대로 받는다. */
    @Test
    void 같은_키_같은_설정이면_저장된_면접을_돌려준다() throws Exception {
        String body = "{\"personaId\": 1, \"companyId\": 12}";
        mockMvc.perform(create(body).header("Idempotency-Key", KEY)).andExpect(status().isOk());
        ArgumentCaptor<InterviewCreateRequestEntity> saved = ArgumentCaptor.forClass(InterviewCreateRequestEntity.class);
        verify(interviewCreateRequestRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getIdempotencyKey()).isEqualTo(UUID.fromString(KEY));
        assertThat(saved.getValue().getInterviewId()).isEqualTo(41L);

        when(interviewCreateRequestRepository.findByUserIdAndIdempotencyKey(USER_ID, UUID.fromString(KEY)))
                .thenReturn(Optional.of(saved.getValue()));
        // 필드 순서가 달라도 같은 설정이다.
        mockMvc.perform(create("{\"companyId\": 12, \"personaId\": 1}").header("Idempotency-Key", KEY.toUpperCase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.interviewId").value(41));
        verify(interviewService).createInterview(eq(USER_ID), any());
        verify(interviewService).getInterviewById(USER_ID, 41L);
    }
}
