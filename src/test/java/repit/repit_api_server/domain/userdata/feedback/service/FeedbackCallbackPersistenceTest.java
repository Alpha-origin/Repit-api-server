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
import repit.repit_api_server.domain.userdata.feedback.dto.response.FrequentWordResponse;
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
                "부작용까지는 못 짚었습니다.", null);

        return new FeedbackCallbackRequest(jobId, sessionId, "succeeded",
                new FeedbackCallbackRequest.Result(overall, List.of(tech, hr), List.of(item)), null);
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
                JsonMapper.shared().valueToTree(axisScores));

        feedbackService.handleCallback(new FeedbackCallbackRequest(jobId, sessionId, "succeeded",
                new FeedbackCallbackRequest.Result(overall, List.of(tech, hr), List.of(item)), null));
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

    /** 컬럼보다 긴 버전을 그대로 넣으면 플러시에서 터지고, 트랜잭션이 롤백돼 채점 결과가 폐기된다. */
    @Test
    void 긴_방식_버전이_와도_결과가_저장된다() {
        FeedbackEntity accepted = givenAccepted();
        String longVersion = "axis-v1+prompt-2026-09-28-hotfix-1+".repeat(3);

        FeedbackCallbackRequest.Overall overall = new FeedbackCallbackRequest.Overall(
                70, 70, 70, "요약", List.of(), List.of(), List.of(), 1, 1,
                JsonMapper.shared().valueToTree(new ScoreBreakdownResponse(longVersion, List.of(), null)));
        feedbackService.handleCallback(new FeedbackCallbackRequest(jobId, sessionId, "succeeded",
                new FeedbackCallbackRequest.Result(overall, null, List.of()), null));
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
        assertThat(feedbackItemRepository.findAllByFeedbackIdOrderBySortOrderAsc(accepted.getFeedbackId()))
                .hasSize(1);
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
