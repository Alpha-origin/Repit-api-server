package repit.repit_api_server.domain.userdata.feedback.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import repit.repit_api_server.domain.company.dto.CompanyBadge;
import repit.repit_api_server.domain.company.dto.CompanySnapshot;
import repit.repit_api_server.domain.company.entity.CompanyEntity;
import repit.repit_api_server.domain.company.entity.enums.CompanyCategory;
import repit.repit_api_server.domain.company.entity.enums.CompanyStatus;
import repit.repit_api_server.domain.company.repository.CompanyRepository;
import repit.repit_api_server.domain.userdata.answer.entity.AnswerEntity;
import repit.repit_api_server.domain.userdata.answer.repository.AnswerRepository;
import repit.repit_api_server.domain.userdata.feedback.dto.request.FeedbackCallbackRequest;
import repit.repit_api_server.domain.userdata.feedback.dto.request.FeedbackSoloRequest;
import repit.repit_api_server.domain.userdata.feedback.dto.response.CompanyFitResponse;
import repit.repit_api_server.domain.userdata.feedback.dto.response.FeedbackAcceptedResponse;
import repit.repit_api_server.domain.userdata.feedback.dto.response.FeedbackResponse;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackEntity;
import repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackStatus;
import repit.repit_api_server.domain.userdata.feedback.repository.FeedbackRepository;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.question.entity.QuestionEntity;
import repit.repit_api_server.domain.userdata.question.entity.enums.Type;
import repit.repit_api_server.domain.userdata.question.repository.QuestionRepository;
import repit.repit_api_server.global.client.AiServerClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 기업을 고른 면접의 피드백. 요청에 기업 정보가 실리고, 콜백의 기업 관점 코멘트가 저장·조회되는지 본다.
 *
 * <p>면접 스냅샷과 companyFit이 jsonb로 실제 스키마에 들어가는지까지 보려고 DB에 쓴다. 롤백된다.
 * 면접의 경로는 스냅샷만 읽지만, 조회 응답의 기업 id는 면접의 company_id 컬럼이라 기업 행을 하나 만든다.
 */
@SpringBootTest
@Transactional
class FeedbackServiceCompanyTest {

    private static final Long USER_ID = 9L;

    private static final CompanySnapshot SNAPSHOT = new CompanySnapshot("3", "샘플커머스", "CONSUMER",
            List.of(new CompanySnapshot.Fact("21", "BUSINESS", "샘플마켓",
                            "판매자와 구매자를 연결하는 온라인 거래 서비스", List.of(), List.of()),
                    new CompanySnapshot.Fact("23", "TALENT", "오너십", "문제를 스스로 정의하고 결과까지 책임진다",
                            List.of("맡은 문제를 끝까지 해결한 경험을 설명함"), List.of("책임을 남에게 돌림"))),
            "샘플커머스는 가상의 기업이다.");

    @Autowired
    private FeedbackService feedbackService;
    @Autowired
    private FeedbackRepository feedbackRepository;
    @Autowired
    private InterviewRepository interviewRepository;
    @Autowired
    private QuestionRepository questionRepository;
    @Autowired
    private AnswerRepository answerRepository;
    @Autowired
    private CompanyRepository companyRepository;

    @MockitoBean
    private AiServerClient aiServerClient;

    @PersistenceContext
    private EntityManager entityManager;

    private final String sessionId = UUID.randomUUID().toString();
    private final String jobId = UUID.randomUUID().toString();
    // 답변이 있는 문항과 답변이 없는 문항. 기업 관점 코멘트는 앞엣것만 근거로 댈 수 있다.
    private String answeredQuestionId;
    private String unansweredQuestionId;

    private Long givenCompany() {
        return companyRepository.save(CompanyEntity.builder()
                .code("fit-" + UUID.randomUUID().toString().substring(0, 8))
                .displayName("샘플커머스")
                .legalName("샘플커머스 주식회사")
                .aliases(List.of())
                .category(CompanyCategory.CONSUMER)
                .status(CompanyStatus.PUBLISHED)
                .interviewerBrief("샘플커머스는 가상의 기업이다.")
                .dataAsOf(LocalDate.parse("2026-10-15"))
                .contentHash("0".repeat(64))
                .build()).getCompanyId();
    }

    /** 채점할 것이 있는 끝난 1:1 면접. 스냅샷이 있으면 그 기업을 고른 면접이다. */
    private InterviewEntity givenFinishedInterview(CompanySnapshot snapshot) {
        InterviewEntity interview = interviewRepository.save(InterviewEntity.builder()
                .userId(USER_ID)
                .mode(InterviewMode.SOLO)
                .status(Status.COMPLETED)
                .sessionId(sessionId)
                .companyId(snapshot == null ? null : givenCompany())
                .companySnapshot(snapshot)
                .build());
        QuestionEntity question = questionRepository.save(QuestionEntity.builder()
                .interviewId(interview.getInterviewId())
                .chatQuestionId(1L)
                .type(Type.ORIGINAL)
                .intention("기술 선택의 근거")
                .content("Redis를 캐시로 두신 이유는?")
                .createdAt(LocalDateTime.now())
                .build());
        answerRepository.save(AnswerEntity.builder()
                .interviewId(interview.getInterviewId())
                .questionId(question.getQuestionId())
                .userId(USER_ID)
                .responseTime(90)
                .content("조회가 쓰기보다 많아서요.")
                .createdAt(LocalDateTime.now())
                .build());
        QuestionEntity unanswered = questionRepository.save(QuestionEntity.builder()
                .interviewId(interview.getInterviewId())
                .chatQuestionId(2L)
                .type(Type.ORIGINAL)
                .intention("장애 대응")
                .content("장애를 겪은 경험이 있나요?")
                .createdAt(LocalDateTime.now())
                .build());
        answeredQuestionId = String.valueOf(question.getQuestionId());
        unansweredQuestionId = String.valueOf(unanswered.getQuestionId());
        return interview;
    }

    /** 다른 면접의 답변 있는 문항. 이 면접의 근거로 대면 안 된다. */
    private String otherInterviewQuestionId() {
        InterviewEntity other = interviewRepository.save(InterviewEntity.builder()
                .userId(USER_ID)
                .mode(InterviewMode.SOLO)
                .status(Status.COMPLETED)
                .sessionId(UUID.randomUUID().toString())
                .build());
        QuestionEntity question = questionRepository.save(QuestionEntity.builder()
                .interviewId(other.getInterviewId())
                .chatQuestionId(1L)
                .type(Type.ORIGINAL)
                .intention("다른 면접")
                .content("다른 면접의 질문")
                .createdAt(LocalDateTime.now())
                .build());
        answerRepository.save(AnswerEntity.builder()
                .interviewId(other.getInterviewId())
                .questionId(question.getQuestionId())
                .userId(USER_ID)
                .responseTime(10)
                .content("다른 면접의 답변")
                .createdAt(LocalDateTime.now())
                .build());
        return String.valueOf(question.getQuestionId());
    }

    private FeedbackSoloRequest requested(CompanySnapshot snapshot) {
        InterviewEntity interview = givenFinishedInterview(snapshot);
        when(aiServerClient.requestSoloFeedback(any()))
                .thenReturn(new FeedbackAcceptedResponse(jobId, sessionId, "accepted", null));

        feedbackService.requestFeedback(USER_ID, interview.getInterviewId());

        ArgumentCaptor<FeedbackSoloRequest> sent = ArgumentCaptor.forClass(FeedbackSoloRequest.class);
        verify(aiServerClient).requestSoloFeedback(sent.capture());
        return sent.getValue();
    }

    private FeedbackEntity callback(JsonNode companyFit) {
        FeedbackCallbackRequest.Overall overall = new FeedbackCallbackRequest.Overall(
                72, 80, 61, "근거를 수치로 설명했습니다.", List.of(), List.of(), List.of(), 1, 1, null);
        feedbackService.handleCallback(new FeedbackCallbackRequest(jobId, sessionId, "succeeded",
                new FeedbackCallbackRequest.Result(overall, null, List.of(), companyFit), null));
        entityManager.flush();
        entityManager.clear();
        return feedbackRepository.findByJobId(jobId).orElseThrow();
    }

    private static JsonNode json(String text) {
        return JsonMapper.shared().readTree(text);
    }

    @Test
    void 기업을_고른_면접은_채점_요청에_스냅샷을_그대로_싣는다() {
        FeedbackSoloRequest sent = requested(SNAPSHOT);

        assertThat(sent.getCompany()).isEqualTo(SNAPSHOT);
        JsonNode company = json(JsonMapper.shared().writeValueAsString(sent)).get("company");
        assertThat(company.get("companyId").stringValue()).isEqualTo("3");
        assertThat(company.get("facts").get(1).get("title").stringValue()).isEqualTo("오너십");
        assertThat(company.has("legalSize")).isFalse();
    }

    @Test
    void 기업이_없는_면접은_company_키를_보내지_않는다() {
        FeedbackSoloRequest sent = requested(null);

        assertThat(json(JsonMapper.shared().writeValueAsString(sent)).has("company")).isFalse();
    }

    @Test
    void 콜백의_기업_관점_코멘트를_저장하고_조회에_싣는다() {
        InterviewEntity interview = givenFinishedInterview(SNAPSHOT);

        FeedbackEntity saved = callback(json("""
                {
                  "comment": "샘플커머스가 중요하게 보는 오너십 관점에서는 원인을 끝까지 추적한 점이 돋보입니다.",
                  "matchedFocus": [
                    { "factId": "23", "kind": "TALENT", "focus": "오너십",
                      "evidence": "장애 원인을 직접 추적했습니다.", "questionIds": ["%s"] }
                  ],
                  "gaps": [],
                  "unknownField": true
                }""".formatted(answeredQuestionId)));

        assertThat(saved.getStatus()).isEqualTo(FeedbackStatus.SUCCEEDED);
        CompanyFitResponse fit = saved.getCompanyFit();
        assertThat(fit.comment()).startsWith("샘플커머스가");
        assertThat(fit.matchedFocus()).singleElement().satisfies(item -> {
            assertThat(item.factId()).isEqualTo("23");
            assertThat(item.focus()).isEqualTo("오너십");
            assertThat(item.questionIds()).containsExactly(answeredQuestionId);
        });
        assertThat(fit.gaps()).isEmpty();

        FeedbackResponse response = feedbackService.getFeedback(USER_ID, interview.getInterviewId());
        assertThat(response.getCompany()).isEqualTo(new CompanyBadge(interview.getCompanyId(), "샘플커머스"));
        assertThat(response.getCompanyFit()).isEqualTo(fit);
    }

    @Test
    void 기업_관점_코멘트가_없거나_모양이_틀려도_피드백은_저장한다() {
        givenFinishedInterview(SNAPSHOT);

        for (JsonNode companyFit : new JsonNode[]{null, json("null"), json("\"문자열\""),
                json("{\"comment\": \"코멘트\", \"matchedFocus\": \"목록이 아님\"}"), json("{\"gaps\": []}")}) {
            FeedbackEntity saved = callback(companyFit);

            assertThat(saved.getStatus()).as(String.valueOf(companyFit)).isEqualTo(FeedbackStatus.SUCCEEDED);
            assertThat(saved.getTotalScore()).isEqualTo(72);
            assertThat(saved.getCompanyFit()).as(String.valueOf(companyFit)).isNull();
        }
    }

    /**
     * 근거는 이 면접에서 답변이 있는 문항이어야 한다. 답하지 않은 문항, 다른 면접의 문항만 댄 항목은 빼고, kind·focus는
     * 모델이 쓴 값 대신 스냅샷 fact에서 채운다. 기본 피드백은 그대로 저장한다.
     */
    @Test
    void 이_면접의_답변_있는_문항만_근거로_남긴다() {
        givenFinishedInterview(SNAPSHOT);
        String otherQuestionId = otherInterviewQuestionId();

        FeedbackEntity saved = callback(json("""
                {
                  "comment": "오너십 관점에서 원인을 끝까지 따라간 설명이 보였습니다.",
                  "matchedFocus": [
                    { "factId": "23", "kind": "BUSINESS", "focus": "지어낸 이름",
                      "evidence": "원인을 직접 추적했습니다.", "questionIds": ["%1$s", "%2$s", "%3$s", "%1$s"] },
                    { "factId": "21", "evidence": "다른 면접 답변만 댔습니다.", "questionIds": ["%3$s"] }
                  ],
                  "gaps": [
                    { "factId": "21", "suggestion": "답하지 않은 문항만 댔습니다.", "questionIds": ["%2$s"] }
                  ]
                }""".formatted(answeredQuestionId, unansweredQuestionId, otherQuestionId)));

        assertThat(saved.getStatus()).isEqualTo(FeedbackStatus.SUCCEEDED);
        assertThat(saved.getTotalScore()).isEqualTo(72);
        CompanyFitResponse fit = saved.getCompanyFit();
        assertThat(fit.matchedFocus()).singleElement().satisfies(item -> {
            assertThat(item.kind()).isEqualTo("TALENT");
            assertThat(item.focus()).isEqualTo("오너십");
            assertThat(item.questionIds()).containsExactly(answeredQuestionId);
        });
        assertThat(fit.gaps()).isEmpty();
    }

    /** 항목이 있었는데 모두 근거가 맞지 않으면 총평만 살리지 않는다. 기본 피드백은 저장한다. */
    @Test
    void 항목이_모두_빠지면_기업_관점_코멘트는_비운다() {
        givenFinishedInterview(SNAPSHOT);

        FeedbackEntity saved = callback(json("""
                {
                  "comment": "오너십이 잘 드러났습니다.",
                  "matchedFocus": [
                    { "factId": "999", "evidence": "없는 fact", "questionIds": ["%1$s"] },
                    { "factId": "23", "evidence": "답하지 않은 문항", "questionIds": ["%2$s"] }
                  ],
                  "gaps": []
                }""".formatted(answeredQuestionId, unansweredQuestionId)));

        assertThat(saved.getStatus()).isEqualTo(FeedbackStatus.SUCCEEDED);
        assertThat(saved.getTotalScore()).isEqualTo(72);
        assertThat(saved.getCompanyFit()).isNull();
    }

    /** 기업을 고르지 않은 면접에 코멘트가 와도 잇지 않는다. */
    @Test
    void 기업이_없는_면접에_온_기업_관점_코멘트는_버린다() {
        InterviewEntity interview = givenFinishedInterview(null);

        FeedbackEntity saved = callback(json("""
                { "comment": "관찰할 기회가 없었습니다.", "matchedFocus": [], "gaps": [] }"""));

        assertThat(saved.getStatus()).isEqualTo(FeedbackStatus.SUCCEEDED);
        assertThat(saved.getCompanyFit()).isNull();
        assertThat(feedbackService.getFeedback(USER_ID, interview.getInterviewId()).getCompanyFit()).isNull();
    }

    @Test
    void 기업이_없는_면접의_피드백_조회에는_기업이_비어_있다() {
        InterviewEntity interview = givenFinishedInterview(null);
        callback(null);

        FeedbackResponse response = feedbackService.getFeedback(USER_ID, interview.getInterviewId());

        assertThat(response.getCompany()).isNull();
        assertThat(response.getCompanyFit()).isNull();
    }
}
