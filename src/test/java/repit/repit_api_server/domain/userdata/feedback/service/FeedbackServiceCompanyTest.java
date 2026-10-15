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
 * 면접의 모든 경로는 스냅샷만 읽으므로 기업 행은 만들지 않는다.
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

    @MockitoBean
    private AiServerClient aiServerClient;

    @PersistenceContext
    private EntityManager entityManager;

    private final String sessionId = UUID.randomUUID().toString();
    private final String jobId = UUID.randomUUID().toString();

    /** 채점할 것이 있는 끝난 1:1 면접. */
    private InterviewEntity givenFinishedInterview(CompanySnapshot snapshot) {
        InterviewEntity interview = interviewRepository.save(InterviewEntity.builder()
                .userId(USER_ID)
                .mode(InterviewMode.SOLO)
                .status(Status.COMPLETED)
                .sessionId(sessionId)
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
        return interview;
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
                      "evidence": "장애 원인을 직접 추적했습니다.", "questionIds": ["901"] }
                  ],
                  "gaps": [],
                  "unknownField": true
                }"""));

        assertThat(saved.getStatus()).isEqualTo(FeedbackStatus.SUCCEEDED);
        CompanyFitResponse fit = saved.getCompanyFit();
        assertThat(fit.comment()).startsWith("샘플커머스가");
        assertThat(fit.matchedFocus()).singleElement().satisfies(item -> {
            assertThat(item.factId()).isEqualTo("23");
            assertThat(item.focus()).isEqualTo("오너십");
            assertThat(item.questionIds()).containsExactly("901");
        });
        assertThat(fit.gaps()).isEmpty();

        FeedbackResponse response = feedbackService.getFeedback(USER_ID, interview.getInterviewId());
        assertThat(response.getCompany()).isEqualTo(new CompanyBadge(3L, "샘플커머스"));
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

    @Test
    void 기업이_없는_면접의_피드백_조회에는_기업이_비어_있다() {
        InterviewEntity interview = givenFinishedInterview(null);
        callback(null);

        FeedbackResponse response = feedbackService.getFeedback(USER_ID, interview.getInterviewId());

        assertThat(response.getCompany()).isNull();
        assertThat(response.getCompanyFit()).isNull();
    }
}
