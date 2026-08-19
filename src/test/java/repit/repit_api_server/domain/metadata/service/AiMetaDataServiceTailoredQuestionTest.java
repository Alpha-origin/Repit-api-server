package repit.repit_api_server.domain.metadata.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import repit.repit_api_server.domain.metadata.dto.response.ResultResponse;
import repit.repit_api_server.domain.metadata.entity.AnalysisDataEntity;
import repit.repit_api_server.domain.metadata.repository.AnalysisDataRepository;
import repit.repit_api_server.domain.userdata.question.dto.response.TailoredQuestionResponse;
import repit.repit_api_server.domain.userdata.question.entity.QuestionTailorEntity;
import repit.repit_api_server.domain.userdata.question.entity.enums.TailorStatus;
import repit.repit_api_server.domain.userdata.question.repository.QuestionTailorRepository;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** 채팅 서버는 분석 jobId만 들고 질문을 가져간다. 그 경로에서 재작성본이 나가는지 확인한다. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiMetaDataServiceTailoredQuestionTest {

    @Mock
    private AnalysisDataRepository analysisDataRepository;
    @Mock
    private QuestionTailorRepository questionTailorRepository;

    private AiMetaDataService service;
    private Map<String, Object> storedResult;

    @BeforeEach
    void setUp() {
        service = new AiMetaDataService(analysisDataRepository, questionTailorRepository, new ObjectMapper());

        storedResult = new LinkedHashMap<>();
        storedResult.put("project_summary", new LinkedHashMap<>(Map.of("overview", "주문 API")));
        List<Object> interview = new ArrayList<>();
        interview.add(generatedQuestion(1, "왜 Redis 를 썼나요?"));
        interview.add(generatedQuestion(2, "왜 WebFlux 를 썼나요?"));
        storedResult.put("interview", interview);

        when(analysisDataRepository.findById("job-1")).thenReturn(Optional.of(AnalysisDataEntity.builder()
                .jobId("job-1")
                .userId(7L)
                .result(storedResult)
                .build()));
    }

    private Map<String, Object> generatedQuestion(int id, String question) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("category", "tech_choice");
        map.put("question", question);
        map.put("expected_answer", "선택 근거와 대안 비교");
        map.put("based_on", List.of("order-api/src/cache.py"));
        return map;
    }

    private QuestionTailorEntity tailor(String analysisJobId) {
        return QuestionTailorEntity.builder()
                .tailorId(1L)
                .interviewId(3L)
                .userId(7L)
                .jobId("tailor-job-1")
                .analysisJobId(analysisJobId)
                .status(TailorStatus.SUCCEEDED)
                .tailored(true)
                .questions(List.of(
                        TailoredQuestionResponse.builder().id(1).question("다시 쓴 Redis 질문").build(),
                        TailoredQuestionResponse.builder().id(2).question("다시 쓴 WebFlux 질문").build()))
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> interviewOf(ResultResponse response) {
        return (List<Map<String, Object>>) ((Map<String, Object>) response.getResult()).get("interview");
    }

    @Test
    void 재작성이_끝나_있으면_질문_본문이_재작성본으로_나간다() {
        when(questionTailorRepository.findTopByAnalysisJobIdAndTailoredIsTrueOrderByCreatedAtDesc("job-1"))
                .thenReturn(Optional.of(tailor("job-1")));

        List<Map<String, Object>> interview = interviewOf(service.getResult("job-1"));

        assertThat(interview).extracting(question -> question.get("question"))
                .containsExactly("다시 쓴 Redis 질문", "다시 쓴 WebFlux 질문");
        // 본문만 바뀐다. 나머지 필드는 snake_case 그대로 유지돼야 채팅 서버가 읽는다.
        assertThat(interview.getFirst().get("expected_answer")).isEqualTo("선택 근거와 대안 비교");
        assertThat(interview.getFirst()).containsKey("based_on");
    }

    @Test
    void 재작성본이_없으면_원질문을_그대로_돌려준다() {
        when(questionTailorRepository.findTopByAnalysisJobIdAndTailoredIsTrueOrderByCreatedAtDesc("job-1"))
                .thenReturn(Optional.empty());

        List<Map<String, Object>> interview = interviewOf(service.getResult("job-1"));

        assertThat(interview).extracting(question -> question.get("question"))
                .containsExactly("왜 Redis 를 썼나요?", "왜 WebFlux 를 썼나요?");
    }

    @Test
    void 다른_분석_결과로_만든_재작성본은_섞지_않는다() {
        when(questionTailorRepository.findTopByAnalysisJobIdAndTailoredIsTrueOrderByCreatedAtDesc("job-1"))
                .thenReturn(Optional.of(tailor("job-other")));

        List<Map<String, Object>> interview = interviewOf(service.getResult("job-1"));

        assertThat(interview).extracting(question -> question.get("question"))
                .containsExactly("왜 Redis 를 썼나요?", "왜 WebFlux 를 썼나요?");
    }

    @Test
    void interviewId를_주면_그_면접의_재작성본으로_맞춘다() {
        when(questionTailorRepository.findTopByInterviewIdAndTailoredIsTrueOrderByCreatedAtDesc(3L))
                .thenReturn(Optional.of(tailor("job-1")));

        List<Map<String, Object>> interview = interviewOf(service.getResult("job-1", 3L));

        assertThat(interview).extracting(question -> question.get("question"))
                .containsExactly("다시 쓴 Redis 질문", "다시 쓴 WebFlux 질문");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 병합은_저장된_분석_결과를_건드리지_않는다() {
        when(questionTailorRepository.findTopByAnalysisJobIdAndTailoredIsTrueOrderByCreatedAtDesc("job-1"))
                .thenReturn(Optional.of(tailor("job-1")));

        service.getResult("job-1");

        // 영속 엔티티의 result를 그대로 고치면 더티 체킹으로 DB의 원질문까지 덮어쓰게 된다.
        List<Map<String, Object>> stored = (List<Map<String, Object>>) storedResult.get("interview");
        assertThat(stored).extracting(question -> question.get("question"))
                .containsExactly("왜 Redis 를 썼나요?", "왜 WebFlux 를 썼나요?");
    }
}
