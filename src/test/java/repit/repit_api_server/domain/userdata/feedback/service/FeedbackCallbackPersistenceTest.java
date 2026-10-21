package repit.repit_api_server.domain.userdata.feedback.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import repit.repit_api_server.domain.userdata.feedback.dto.request.FeedbackCallbackRequest;
import repit.repit_api_server.domain.userdata.feedback.dto.response.AxisScoreResponse;
import repit.repit_api_server.domain.userdata.feedback.dto.response.CompetencyScoreResponse;
import repit.repit_api_server.domain.userdata.feedback.dto.response.CredibilityResponse;
import repit.repit_api_server.domain.userdata.feedback.dto.response.FrequentWordResponse;
import repit.repit_api_server.domain.userdata.feedback.dto.response.ResponseSummaryResponse;
import repit.repit_api_server.domain.userdata.feedback.dto.response.ScoreBreakdownResponse;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackEntity;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackItemEntity;
import repit.repit_api_server.domain.userdata.feedback.entity.FeedbackPersonaEntity;
import repit.repit_api_server.domain.userdata.feedback.entity.enums.FeedbackStatus;
import repit.repit_api_server.domain.userdata.feedback.repository.FeedbackItemRepository;
import repit.repit_api_server.domain.userdata.feedback.repository.FeedbackPersonaRepository;
import repit.repit_api_server.domain.userdata.feedback.repository.FeedbackRepository;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.answer.entity.AnswerEntity;
import repit.repit_api_server.domain.userdata.answer.repository.AnswerRepository;
import repit.repit_api_server.domain.userdata.question.entity.QuestionEntity;
import repit.repit_api_server.domain.userdata.question.entity.enums.Type;
import repit.repit_api_server.domain.userdata.question.repository.QuestionRepository;
import repit.repit_api_server.domain.userdata.feedback.dto.response.FeedbackAcceptedResponse;
import repit.repit_api_server.global.client.AiServerClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 채점 콜백이 실제 DB의 각 컬럼까지 들어가는지 확인한다.
 *
 * <p>목으로는 리포지토리를 불렀다는 것까지만 확인된다. 컬럼 이름이 어긋나거나 jsonb 직렬화가
 * 막히는 것은 플러시 시점에야 드러나므로, 여기서는 실제 스키마에 쓰고 영속성 컨텍스트를 비운 뒤
 * 다시 읽어 확인한다. 테스트 트랜잭션은 끝나면 롤백된다.
 */
@SpringBootTest
@Transactional
class FeedbackCallbackPersistenceTest {

    @Autowired
    private FeedbackService feedbackService;
    @Autowired
    private FeedbackRepository feedbackRepository;
    @Autowired
    private FeedbackItemRepository feedbackItemRepository;
    @Autowired
    private FeedbackPersonaRepository feedbackPersonaRepository;
    @Autowired
    private InterviewRepository interviewRepository;
    @Autowired
    private QuestionRepository questionRepository;
    @Autowired
    private AnswerRepository answerRepository;

    // 채점 요청은 분석 서버를 부른다. 여기서 확인할 것은 그 응답을 받아 남기는 접수 기록이다.
    @MockitoBean
    private AiServerClient aiServerClient;

    @PersistenceContext
    private EntityManager entityManager;

    private static final String FOLLOW_UP_QUESTION = "캐시와 DB가 어긋나면 어느 쪽을 기준으로 맞추시겠습니까?";

    private final String sessionId = UUID.randomUUID().toString();
    private final String jobId = UUID.randomUUID().toString();

    private FeedbackEntity givenAccepted() {
        return feedbackRepository.save(FeedbackEntity.builder()
                .interviewId(999_999L)
                .userId(9L)
                .sessionId(sessionId)
                .jobId(jobId)
                .status(FeedbackStatus.PENDING)
                .build());
    }

    private FeedbackCallbackRequest multiCallback() {
        FeedbackCallbackRequest.Overall overall = new FeedbackCallbackRequest.Overall(
                72, 80, 61, "면접관이 바뀐 뒤 설명이 달라졌습니다.",
                List.of("캐시 도입 배경을 수치와 함께 설명함"),
                List.of("설명이 엇갈림"),
                List.of(new FrequentWordResponse("성능", 7)),
                6, 7, null);

        FeedbackCallbackRequest.Persona tech = new FeedbackCallbackRequest.Persona(
                11L, "TECH", 78, "대안 검토가 얕습니다.",
                List.of("측정값을 근거로 제시함"), List.of("탈락 이유가 없음"), 3, 3, null);
        FeedbackCallbackRequest.Persona hr = new FeedbackCallbackRequest.Persona(
                12L, "HR", 70, "동기가 추상적입니다.", List.of(), List.of(), 2, 2, null);

        FeedbackCallbackRequest.Item item = new FeedbackCallbackRequest.Item(
                "901", 11L, "Redis를 캐시로 두신 이유는?", "기술 선택의 근거",
                "조회가 쓰기보다 많아서요.", "지연을 수치로 제시한다.",
                List.of("p99 지연을 근거로 든 점"), List.of("무효화 전략 미언급"),
                "부작용까지는 못 짚었습니다.", null,
                JsonMapper.shared().valueToTree(FOLLOW_UP_QUESTION), null, null);

        return new FeedbackCallbackRequest(jobId, sessionId, "succeeded",
                new FeedbackCallbackRequest.Result(overall, List.of(tech, hr), List.of(item), null), null);
    }

    /** 쓴 것을 DB에서 다시 읽어오게 한다. 컨텍스트에 남아 있는 것을 그대로 보면 저장 여부를 알 수 없다. */
    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void 종합_평가가_컬럼마다_저장된다() {
        FeedbackEntity accepted = givenAccepted();

        feedbackService.handleCallback(multiCallback());
        flushAndClear();

        FeedbackEntity saved = feedbackRepository.findById(accepted.getFeedbackId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(FeedbackStatus.SUCCEEDED);
        assertThat(saved.getTotalScore()).isEqualTo(72);
        assertThat(saved.getIntentAlignmentScore()).isEqualTo(80);
        assertThat(saved.getReliabilityScore()).isEqualTo(61);
        assertThat(saved.getSummary()).isEqualTo("면접관이 바뀐 뒤 설명이 달라졌습니다.");
        assertThat(saved.getStrengths()).containsExactly("캐시 도입 배경을 수치와 함께 설명함");
        assertThat(saved.getImprovements()).containsExactly("설명이 엇갈림");
        assertThat(saved.getAnsweredCount()).isEqualTo(6);
        assertThat(saved.getQuestionCount()).isEqualTo(7);
        assertThat(saved.getErrorStatusCode()).isNull();
        assertThat(saved.getErrorMessage()).isNull();

        // jsonb에 객체 배열로 들어간다. 직렬화가 막히면 플러시에서 터진다.
        assertThat(saved.getFrequentWords()).hasSize(1);
        assertThat(saved.getFrequentWords().getFirst().getWord()).isEqualTo("성능");
        assertThat(saved.getFrequentWords().getFirst().getCount()).isEqualTo(7);
    }

    @Test
    void 문항별_피드백이_컬럼마다_저장된다() {
        FeedbackEntity accepted = givenAccepted();

        feedbackService.handleCallback(multiCallback());
        flushAndClear();

        List<FeedbackItemEntity> items =
                feedbackItemRepository.findAllByFeedbackIdOrderBySortOrderAsc(accepted.getFeedbackId());
        assertThat(items).hasSize(1);

        FeedbackItemEntity item = items.getFirst();
        assertThat(item.getQuestionId()).isEqualTo("901");
        assertThat(item.getSortOrder()).isZero();
        assertThat(item.getPersonaId()).isEqualTo(11L);
        assertThat(item.getQuestionContent()).isEqualTo("Redis를 캐시로 두신 이유는?");
        assertThat(item.getIntention()).isEqualTo("기술 선택의 근거");
        assertThat(item.getUserAnswer()).isEqualTo("조회가 쓰기보다 많아서요.");
        assertThat(item.getModelAnswer()).isEqualTo("지연을 수치로 제시한다.");
        assertThat(item.getStrengths()).containsExactly("p99 지연을 근거로 든 점");
        assertThat(item.getImprovements()).containsExactly("무효화 전략 미언급");
        assertThat(item.getComment()).isEqualTo("부작용까지는 못 짚었습니다.");
        assertThat(item.getFollowUpQuestion()).isEqualTo(FOLLOW_UP_QUESTION);
    }

    /** 구버전 분석 서버는 예상 꼬리질문을 보내지 않는다. 그래도 문항은 저장되고 그 칸만 빈다. */
    @Test
    void 예상_꼬리질문이_없는_콜백도_문항이_저장된다() {
        FeedbackEntity accepted = givenAccepted();
        FeedbackCallbackRequest.Item item = new FeedbackCallbackRequest.Item(
                "901", 11L, "질문", "의도", "답변", "모범답변", List.of(), List.of(), "총평", null, null, null, null);
        FeedbackCallbackRequest.Overall overall = new FeedbackCallbackRequest.Overall(
                70, 70, 70, "요약", List.of(), List.of(), List.of(), 1, 1, null);

        feedbackService.handleCallback(new FeedbackCallbackRequest(jobId, sessionId, "succeeded",
                new FeedbackCallbackRequest.Result(overall, null, List.of(item), null), null));
        flushAndClear();

        assertThat(feedbackRepository.findById(accepted.getFeedbackId()).orElseThrow().getStatus())
                .isEqualTo(FeedbackStatus.SUCCEEDED);
        List<FeedbackItemEntity> items =
                feedbackItemRepository.findAllByFeedbackIdOrderBySortOrderAsc(accepted.getFeedbackId());
        assertThat(items).hasSize(1);
        assertThat(items.getFirst().getFollowUpQuestion()).isNull();
    }

    @Test
    void 면접관별_종합이_컬럼마다_저장된다() {
        FeedbackEntity accepted = givenAccepted();

        feedbackService.handleCallback(multiCallback());
        flushAndClear();

        List<FeedbackPersonaEntity> personas =
                feedbackPersonaRepository.findAllByFeedbackIdOrderBySortOrderAsc(accepted.getFeedbackId());
        assertThat(personas).hasSize(2);

        FeedbackPersonaEntity tech = personas.getFirst();
        assertThat(tech.getPersonaId()).isEqualTo(11L);
        assertThat(tech.getPersonaRole()).isEqualTo("TECH");
        assertThat(tech.getSortOrder()).isZero();
        assertThat(tech.getScore()).isEqualTo(78);
        assertThat(tech.getComment()).isEqualTo("대안 검토가 얕습니다.");
        assertThat(tech.getStrengths()).containsExactly("측정값을 근거로 제시함");
        assertThat(tech.getImprovements()).containsExactly("탈락 이유가 없음");
        assertThat(tech.getAnsweredCount()).isEqualTo(3);
        assertThat(tech.getQuestionCount()).isEqualTo(3);
        // 분석 서버가 보낸 순서가 곧 면접 진행 순서다.
        assertThat(personas.get(1).getSortOrder()).isEqualTo(1);
    }

    /** 산출 근거는 jsonb에 객체로 들어간다. 직렬화가 막히거나 null 값이 빠지면 화면의 계산식이 깨진다. */
    @Test
    void 점수_산출_근거가_저장된다() {
        FeedbackEntity accepted = givenAccepted();

        JsonNode breakdown = JsonMapper.shared().valueToTree(new ScoreBreakdownResponse("axis-v1", List.of(
                new AxisScoreResponse("INTENT", 88, 35),
                new AxisScoreResponse("DEPTH", 38, 25),
                new AxisScoreResponse("SPECIFICITY", 63, 25),
                new AxisScoreResponse("ACCURACY", null, null)), 75));
        Map<String, Integer> axisScores = new LinkedHashMap<>();
        axisScores.put("INTENT", 88);
        axisScores.put("ACCURACY", null);

        FeedbackCallbackRequest.Overall overall = new FeedbackCallbackRequest.Overall(
                63, 88, 69, "요약", List.of(), List.of(), List.of(), 1, 1, breakdown);
        FeedbackCallbackRequest.Persona tech = new FeedbackCallbackRequest.Persona(
                11L, "TECH", 63, "총평", List.of(), List.of(), 1, 1, breakdown);
        FeedbackCallbackRequest.Persona hr = new FeedbackCallbackRequest.Persona(
                12L, "HR", null, "담당 답변 없음", List.of(), List.of(), 0, 0, null);
        FeedbackCallbackRequest.Item item = new FeedbackCallbackRequest.Item(
                "901", 11L, "질문", "의도", "답변", "모범답변", List.of(), List.of(), "총평",
                JsonMapper.shared().valueToTree(axisScores), null, null, null);

        feedbackService.handleCallback(new FeedbackCallbackRequest(jobId, sessionId, "succeeded",
                new FeedbackCallbackRequest.Result(overall, List.of(tech, hr), List.of(item), null), null));
        flushAndClear();

        FeedbackEntity saved = feedbackRepository.findById(accepted.getFeedbackId()).orElseThrow();
        assertThat(saved.getScoringVersion()).isEqualTo("axis-v1");
        assertThat(saved.getScoreBreakdown().getConsistencyScore()).isEqualTo(75);
        assertThat(saved.getScoreBreakdown().getAxes()).hasSize(4);
        AxisScoreResponse accuracy = saved.getScoreBreakdown().getAxes().getLast();
        assertThat(accuracy.getAxis()).isEqualTo("ACCURACY");
        assertThat(accuracy.getScore()).isNull();
        assertThat(accuracy.getWeight()).isNull();

        List<FeedbackPersonaEntity> personas =
                feedbackPersonaRepository.findAllByFeedbackIdOrderBySortOrderAsc(accepted.getFeedbackId());
        assertThat(personas.getFirst().getScoreBreakdown().getScoringVersion()).isEqualTo("axis-v1");
        assertThat(personas.get(1).getScore()).isNull();
        assertThat(personas.get(1).getScoreBreakdown()).isNull();

        FeedbackItemEntity savedItem =
                feedbackItemRepository.findAllByFeedbackIdOrderBySortOrderAsc(accepted.getFeedbackId()).getFirst();
        assertThat(savedItem.getAxisScores())
                .containsEntry("INTENT", 88)
                .containsEntry("ACCURACY", null);
    }

    /**
     * competency-v1 콜백. 연동 계약(01 문서) 4장 예시 본문이다. 종합 점수만 축 점수와 가중치로 다시 계산한 값(66)으로 바꿨다 —
     * 예시의 68은 계약의 보장 식과 어긋난다.
     */
    private static final String COMPETENCY_CALLBACK = """
            {
              "jobId": "%s",
              "sessionId": "%s",
              "status": "succeeded",
              "result": {
                "overall": {
                  "totalScore": 66,
                  "intentAlignmentScore": 88,
                  "reliabilityScore": 75,
                  "scoreBreakdown": {
                    "scoringVersion": "competency-v1",
                    "axes": [
                      { "axis": "PROBLEM_SOLVING", "score": 75, "weight": 30, "verdict": "SOLID", "evidenceCount": 3 },
                      { "axis": "KNOWLEDGE", "score": 58, "weight": 25, "verdict": "BORDERLINE", "evidenceCount": 2 },
                      { "axis": "OWNERSHIP", "score": 70, "weight": 25, "verdict": "SOLID", "evidenceCount": 3 },
                      { "axis": "COLLABORATION", "score": null, "weight": null, "verdict": null, "evidenceCount": 0 },
                      { "axis": "GROWTH", "score": 50, "weight": 10, "verdict": "BORDERLINE", "evidenceCount": 1 }
                    ],
                    "consistencyScore": 75,
                    "response": { "score": 88, "hitCount": 5, "gradedCount": 6 },
                    "motivation": null,
                    "credibility": {
                      "score": 75,
                      "issues": [
                        {
                          "kind": "ROLE",
                          "questionIds": ["101", "105"],
                          "quotes": ["결제 모듈은 제가 전부 맡았습니다", "결제 쪽은 동료가 개발했고"]
                        }
                      ]
                    }
                  },
                  "summary": "요약",
                  "strengths": [],
                  "improvements": [],
                  "frequentWords": [],
                  "answeredCount": 6,
                  "questionCount": 6
                },
                "feedbacks": [
                  {
                    "questionId": "101",
                    "questionContent": "재고 차감에 Redis 분산락을 쓴 이유는 무엇인가요?",
                    "intention": "재고 차감에 분산락을 고른 이유를 DB 락과 비교해 설명할 수 있는지",
                    "userAnswer": "재고 차감 경합이 잦아 DB 비관적 락은 커넥션을 오래 잡아서 분산락을 썼고, TTL이 지나면 락이 자동으로 풀리고",
                    "comment": "총평",
                    "responseLevel": 4,
                    "axisScores": { "PROBLEM_SOLVING": 75, "KNOWLEDGE": 100 },
                    "competencies": [
                      {
                        "competency": "PROBLEM_SOLVING",
                        "role": "PRIMARY",
                        "level": 3,
                        "score": 75,
                        "evidence": [
                          { "slot": "reason", "quote": "재고 차감 경합이 잦아" },
                          { "slot": "alternatives", "quote": "DB 비관적 락은 커넥션을 오래 잡아서" }
                        ]
                      },
                      {
                        "competency": "KNOWLEDGE",
                        "role": "SECONDARY",
                        "level": 4,
                        "score": 100,
                        "evidence": [ { "slot": "mechanism", "quote": "TTL이 지나면 락이 자동으로 풀리고" } ]
                      }
                    ]
                  }
                ]
              }
            }
            """;

    /** 역량 축 결과는 새 필드가 저장되고, 조회 응답에 그대로 나간다. 비어 있는 지원 동기는 키가 없다. */
    @Test
    void competency_v1_결과가_저장되고_조회_응답에_그대로_나간다() {
        FeedbackEntity accepted = givenAccepted();
        FeedbackCallbackRequest callback = JsonMapper.shared().readValue(
                COMPETENCY_CALLBACK.formatted(jobId, sessionId), FeedbackCallbackRequest.class);

        feedbackService.handleCallback(callback);
        flushAndClear();

        FeedbackEntity saved = feedbackRepository.findById(accepted.getFeedbackId()).orElseThrow();
        assertThat(saved.getScoringVersion()).isEqualTo("competency-v1");
        assertThat(saved.getTotalScore()).isEqualTo(66);
        ScoreBreakdownResponse breakdown = saved.getScoreBreakdown();
        assertThat(breakdown.getAxes())
                .extracting(AxisScoreResponse::getAxis, AxisScoreResponse::getScore, AxisScoreResponse::getWeight,
                        AxisScoreResponse::getVerdict, AxisScoreResponse::getEvidenceCount)
                .containsExactly(
                        tuple("PROBLEM_SOLVING", 75, 30, "SOLID", 3),
                        tuple("KNOWLEDGE", 58, 25, "BORDERLINE", 2),
                        tuple("OWNERSHIP", 70, 25, "SOLID", 3),
                        tuple("COLLABORATION", null, null, null, 0),
                        tuple("GROWTH", 50, 10, "BORDERLINE", 1));
        assertThat(breakdown.getResponse()).isEqualTo(new ResponseSummaryResponse(88, 5, 6));
        assertThat(breakdown.getMotivation()).isNull();
        assertThat(breakdown.getCredibility()).isEqualTo(new CredibilityResponse(75, List.of(
                new CredibilityResponse.Issue("ROLE", List.of("101", "105"),
                        List.of("결제 모듈은 제가 전부 맡았습니다", "결제 쪽은 동료가 개발했고")))));

        FeedbackItemEntity item =
                feedbackItemRepository.findAllByFeedbackIdOrderBySortOrderAsc(accepted.getFeedbackId()).getFirst();
        assertThat(item.getResponseLevel()).isEqualTo(4);
        // jsonb는 키 순서를 지키지 않는다.
        assertThat(item.getAxisScores()).containsOnly(Map.entry("PROBLEM_SOLVING", 75), Map.entry("KNOWLEDGE", 100));
        assertThat(item.getCompetencies()).containsExactly(
                new CompetencyScoreResponse("PROBLEM_SOLVING", "PRIMARY", 3, 75, List.of(
                        new CompetencyScoreResponse.Evidence("reason", "재고 차감 경합이 잦아"),
                        new CompetencyScoreResponse.Evidence("alternatives", "DB 비관적 락은 커넥션을 오래 잡아서"))),
                new CompetencyScoreResponse("KNOWLEDGE", "SECONDARY", 4, 100, List.of(
                        new CompetencyScoreResponse.Evidence("mechanism", "TTL이 지나면 락이 자동으로 풀리고"))));

        JsonNode body = JsonMapper.shared().valueToTree(feedbackService.getFeedback(9L, 999_999L));
        JsonNode responseBreakdown = body.get("scoreBreakdown");
        assertThat(responseBreakdown.get("axes").get(3).has("verdict")).isFalse();
        assertThat(responseBreakdown.get("axes").get(3).get("evidenceCount").intValue()).isZero();
        assertThat(responseBreakdown.get("response").get("hitCount").intValue()).isEqualTo(5);
        assertThat(responseBreakdown.has("motivation")).isFalse();
        assertThat(responseBreakdown.get("credibility").get("issues").get(0).get("kind").stringValue())
                .isEqualTo("ROLE");
        JsonNode responseItem = body.get("feedbacks").get(0);
        assertThat(responseItem.get("responseLevel").intValue()).isEqualTo(4);
        assertThat(responseItem.get("competencies").get(1).get("evidence").get(0).get("quote").stringValue())
                .isEqualTo("TTL이 지나면 락이 자동으로 풀리고");
    }

    /** axis-v4 결과에는 새 필드가 없다. 조회 응답에도 키가 생기지 않아 지금 화면이 그대로다. */
    @Test
    void axis_v4_결과의_조회_응답에는_새_키가_없다() {
        givenAccepted();
        JsonNode breakdown = JsonMapper.shared().valueToTree(new ScoreBreakdownResponse("axis-v4", List.of(
                new AxisScoreResponse("INTENT", 88, 35)), 75));
        FeedbackCallbackRequest.Overall overall = new FeedbackCallbackRequest.Overall(
                88, 88, 69, "요약", List.of(), List.of(), List.of(), 1, 1, breakdown);
        FeedbackCallbackRequest.Item item = new FeedbackCallbackRequest.Item(
                "901", null, "질문", "의도", "답변", "모범답변", List.of(), List.of(), "총평", null, null, null, null);

        feedbackService.handleCallback(new FeedbackCallbackRequest(jobId, sessionId, "succeeded",
                new FeedbackCallbackRequest.Result(overall, null, List.of(item), null), null));
        flushAndClear();

        JsonNode body = JsonMapper.shared().valueToTree(feedbackService.getFeedback(9L, 999_999L));
        assertThat(body.get("scoreBreakdown").get("axes").get(0).has("verdict")).isFalse();
        assertThat(body.get("scoreBreakdown").get("axes").get(0).has("evidenceCount")).isFalse();
        assertThat(body.get("scoreBreakdown").has("response")).isFalse();
        assertThat(body.get("scoreBreakdown").has("credibility")).isFalse();
        assertThat(body.get("feedbacks").get(0).get("competencies").isNull()).isTrue();
    }

    /** 컬럼보다 긴 버전을 그대로 넣으면 플러시에서 터지고, 트랜잭션이 롤백돼 채점 결과가 폐기된다. */
    @Test
    void 긴_방식_버전이_와도_결과가_저장된다() {
        FeedbackEntity accepted = givenAccepted();
        String longVersion = "axis-v1+prompt-2026-09-28-hotfix-1+".repeat(3);

        FeedbackCallbackRequest.Overall overall = new FeedbackCallbackRequest.Overall(
                70, 70, 70, "요약", List.of(), List.of(), List.of(), 1, 1,
                JsonMapper.shared().valueToTree(new ScoreBreakdownResponse(longVersion, List.of(), null)));
        feedbackService.handleCallback(new FeedbackCallbackRequest(jobId, sessionId, "succeeded",
                new FeedbackCallbackRequest.Result(overall, null, List.of(), null), null));
        flushAndClear();

        FeedbackEntity saved = feedbackRepository.findById(accepted.getFeedbackId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(FeedbackStatus.SUCCEEDED);
        assertThat(saved.getScoringVersion()).hasSize(64);
        // 원래 값은 산출 근거에 그대로 남는다.
        assertThat(saved.getScoreBreakdown().getScoringVersion()).isEqualTo(longVersion);
    }

    @Test
    void 콜백이_다시_와도_문항과_면접관이_중복되지_않는다() {
        FeedbackEntity accepted = givenAccepted();

        feedbackService.handleCallback(multiCallback());
        flushAndClear();
        feedbackService.handleCallback(multiCallback());
        flushAndClear();

        // 지우고 다시 넣는다. 지운 것이 새로 넣은 것보다 늦게 반영되면 방금 넣은 행까지 사라진다.
        List<FeedbackItemEntity> items =
                feedbackItemRepository.findAllByFeedbackIdOrderBySortOrderAsc(accepted.getFeedbackId());
        assertThat(items).hasSize(1);
        assertThat(items.getFirst().getFollowUpQuestion()).isEqualTo(FOLLOW_UP_QUESTION);
        assertThat(feedbackPersonaRepository.findAllByFeedbackIdOrderBySortOrderAsc(accepted.getFeedbackId()))
                .hasSize(2);
    }

    @Test
    void 실패_콜백은_사유가_저장된다() {
        FeedbackEntity accepted = givenAccepted();

        feedbackService.handleCallback(new FeedbackCallbackRequest(jobId, sessionId, "failed", null,
                new FeedbackCallbackRequest.Error(503, "모델 호출이 실패했습니다.")));
        flushAndClear();

        FeedbackEntity saved = feedbackRepository.findById(accepted.getFeedbackId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(FeedbackStatus.FAILED);
        assertThat(saved.getErrorStatusCode()).isEqualTo(503);
        assertThat(saved.getErrorMessage()).isEqualTo("모델 호출이 실패했습니다.");
    }

    /**
     * 접수 기록이 없는 콜백. 202 응답을 받지 못해 행이 남지 않았을 때다.
     * 여기서 받아내지 못하면 분석 서버가 두 번 시도한 뒤 결과를 영구 폐기한다.
     */
    @Test
    void 접수_기록이_없어도_세션으로_면접을_찾아_저장한다() {
        InterviewEntity interview = interviewRepository.save(InterviewEntity.builder()
                .userId(9L)
                .mode(InterviewMode.MULTI)
                .status(Status.COMPLETED)
                .sessionId(sessionId)
                .build());

        feedbackService.handleCallback(multiCallback());
        flushAndClear();

        FeedbackEntity saved = feedbackRepository
                .findTopBySessionIdOrderByCreatedAtDesc(sessionId).orElseThrow();
        assertThat(saved.getInterviewId()).isEqualTo(interview.getInterviewId());
        assertThat(saved.getUserId()).isEqualTo(9L);
        assertThat(saved.getJobId()).isEqualTo(jobId);
        assertThat(saved.getStatus()).isEqualTo(FeedbackStatus.SUCCEEDED);
        assertThat(feedbackItemRepository.findAllByFeedbackIdOrderBySortOrderAsc(saved.getFeedbackId()))
                .hasSize(1);
    }

    /**
     * 채점 결과가 접수 응답보다 먼저 도착하는 경우. 분석 서버 응답이 늦어질 수 있어 실제로 벌어진다.
     *
     * <p>콜백이 먼저 오면 세션으로 면접을 되짚어 결과가 담긴 행이 이미 만들어져 있다. 그 위에
     * 접수 행을 새로 넣으면 job_id 유일 색인에 걸려 요청이 통째로 실패한다.
     */
    @Test
    void 결과가_접수보다_먼저_도착해도_접수가_요청을_깨뜨리지_않는다() {
        InterviewEntity interview = givenFinishedInterview();

        // 202를 돌려주기 직전에 결과 콜백이 먼저 도착한 상황을 만든다.
        when(aiServerClient.requestSoloFeedback(any())).thenAnswer(call -> {
            feedbackService.handleCallback(multiCallback());
            return new FeedbackAcceptedResponse(jobId, sessionId, "accepted", null);
        });

        feedbackService.requestFeedbackForFinishedInterview(interview.getInterviewId());
        flushAndClear();

        // 접수 행을 덧붙이지 않는다. 붙이면 결과가 빈 PENDING 행이 최신이 되어 결과를 가린다.
        FeedbackEntity saved = feedbackRepository
                .findTopBySessionIdOrderByCreatedAtDesc(sessionId).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(FeedbackStatus.SUCCEEDED);
        assertThat(saved.getTotalScore()).isEqualTo(72);
        assertThat(feedbackItemRepository.findAllByFeedbackIdOrderBySortOrderAsc(saved.getFeedbackId()))
                .hasSize(1);
    }

    /** 채점할 것이 있는 끝난 면접. 질문과 답변이 없으면 요청 전에 걸러진다. */
    private InterviewEntity givenFinishedInterview() {
        InterviewEntity interview = interviewRepository.save(InterviewEntity.builder()
                .userId(9L)
                .mode(InterviewMode.SOLO)
                .status(Status.COMPLETED)
                .sessionId(sessionId)
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
                .userId(9L)
                .responseTime(90)
                .content("조회가 쓰기보다 많아서요.")
                .createdAt(LocalDateTime.now())
                .build());

        return interview;
    }
}
