package repit.repit_api_server.domain.metadata.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import repit.repit_api_server.domain.metadata.dto.request.CallbackSuccessRequest;
import repit.repit_api_server.domain.metadata.dto.response.ResultResponse;
import repit.repit_api_server.domain.metadata.entity.AnalysisDataEntity;
import repit.repit_api_server.domain.metadata.repository.AnalysisDataRepository;
import repit.repit_api_server.domain.userdata.question.dto.response.TailoredQuestionResponse;
import repit.repit_api_server.domain.userdata.question.entity.QuestionTailorEntity;
import repit.repit_api_server.domain.userdata.question.repository.QuestionTailorRepository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AiMetaDataService {

    private static final Logger log = LoggerFactory.getLogger(AiMetaDataService.class);

    private final AnalysisDataRepository analysisDataRepository;
    private final QuestionTailorRepository questionTailorRepository;
    private final ObjectMapper objectMapper;

    // 분석 요청 시점에 작업 소유자를 먼저 기록해둔다. 결과는 콜백에서 채워진다.
    public void registerJob(String jobId, Long userId) {
        if (jobId == null || userId == null) {
            return;
        }
        AnalysisDataEntity data = analysisDataRepository.findById(jobId)
                .orElseGet(() -> AnalysisDataEntity.builder().jobId(jobId).build());
        data.setUserId(userId);
        analysisDataRepository.save(data);
    }

    @Transactional
    public void saveResult(CallbackSuccessRequest request) {
        // registerJob으로 이미 저장된 행이 있으면 userId를 유지한 채 결과만 채운다.
        AnalysisDataEntity data = analysisDataRepository.findById(request.getJob_id())
                .orElseGet(() -> AnalysisDataEntity.builder().jobId(request.getJob_id()).build());
        data.setResult(request.getResult());
        analysisDataRepository.save(data);
    }

    public ResultResponse getResult(String jobId) {
        return getResult(jobId, null);
    }

    /**
     * 채팅 서버는 면접 준비 때 받은 분석 jobId만 들고 질문을 가져간다.
     * 그래서 재작성(/questions/tailor)이 끝나 있으면 여기서 본문을 갈아끼워 돌려준다.
     * 한 분석 결과로 여러 면접을 볼 수 있으므로, 호출자가 어느 면접인지 아는 경우 interviewId를 함께 주면 정확해진다.
     */
    public ResultResponse getResult(String jobId, Long interviewId) {
        Object result = analysisDataRepository.findById(jobId)
                .map(AnalysisDataEntity::getResult)
                .orElse(null);
        if (result == null) {
            return new ResultResponse(null);
        }

        List<TailoredQuestionResponse> tailored = findTailoredQuestions(jobId, interviewId);
        if (tailored.isEmpty()) {
            return new ResultResponse(result);
        }
        return new ResultResponse(applyTailoredQuestions(result, tailored));
    }

    private List<TailoredQuestionResponse> findTailoredQuestions(String jobId, Long interviewId) {
        QuestionTailorEntity tailor = (interviewId == null
                ? questionTailorRepository.findTopByAnalysisJobIdAndTailoredIsTrueOrderByCreatedAtDesc(jobId)
                : questionTailorRepository.findTopByInterviewIdAndTailoredIsTrueOrderByCreatedAtDesc(interviewId))
                .orElse(null);

        if (tailor == null || tailor.getQuestions() == null) {
            return List.of();
        }
        // 다른 분석 결과로 만든 재작성본을 섞지 않는다.
        if (!jobId.equals(tailor.getAnalysisJobId())) {
            log.warn("재작성본의 분석 작업이 조회 대상과 달라 원질문을 그대로 돌려줍니다. jobId={}, tailorAnalysisJobId={}",
                    jobId, tailor.getAnalysisJobId());
            return List.of();
        }
        return tailor.getQuestions();
    }

    /**
     * 바뀌는 것은 질문 본문뿐이라 result의 나머지 구조는 손대지 않는다.
     * convertValue가 새 객체 그래프를 만들어주므로 영속 상태의 result를 건드리지 않는다.
     */
    private Object applyTailoredQuestions(Object result, List<TailoredQuestionResponse> tailored) {
        Map<String, Object> copied = objectMapper.convertValue(result, new TypeReference<>() {
        });

        Object interview = copied.get("interview");
        if (!(interview instanceof List<?> questions)) {
            return copied;
        }

        Map<Integer, String> rewritten = new HashMap<>();
        for (TailoredQuestionResponse question : tailored) {
            if (question.getId() != null && question.getQuestion() != null) {
                rewritten.put(question.getId(), question.getQuestion());
            }
        }

        for (Object element : questions) {
            if (!(element instanceof Map<?, ?> question)) {
                continue;
            }
            Object id = question.get("id");
            if (!(id instanceof Number number)) {
                continue;
            }
            String replacement = rewritten.get(number.intValue());
            if (replacement != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> mutable = (Map<String, Object>) question;
                mutable.put("question", replacement);
            }
        }
        return copied;
    }
}
