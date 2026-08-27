package repit.repit_api_server.domain.userdata.answer.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.CrossOrigin;
import repit.repit_api_server.domain.userdata.answer.dto.request.AnswerRequest;
import repit.repit_api_server.domain.userdata.answer.dto.response.AnswerResponse;
import repit.repit_api_server.domain.userdata.answer.entity.AnswerEntity;
import repit.repit_api_server.domain.userdata.answer.repository.AnswerRepository;
import repit.repit_api_server.domain.userdata.question.repository.QuestionRepository;
import repit.repit_api_server.global.client.AuthServerClient;
import repit.repit_api_server.global.exception.BusinessException;
import repit.repit_api_server.global.response.UserResponse;

import java.time.LocalDateTime;
import java.util.List;

@CrossOrigin(origins = "http://localhost:5173")
@Service
@RequiredArgsConstructor
public class AnswerService {
    private final QuestionRepository questionRepository;
    private final AnswerRepository answerRepository;
    private final AuthServerClient authServerClient;

    public AnswerResponse createAnswer(String authorization, AnswerRequest request) {
        UserResponse user = authServerClient.getUser(authorization);
        // 없는 질문에 달린 답변은 어디에도 매달 수 없다. 저장하지 않았다는 것을 알려야 한다 —
        // 성공으로 답하면 클라이언트는 답변이 남은 줄 알고 넘어가고, 그대로 사라진다.
        questionRepository.findById(request.getQuestionId())
                .orElseThrow(() -> BusinessException.notFound("질문을 찾을 수 없습니다"));

        AnswerEntity answer = AnswerEntity.builder()
                .interviewId(request.getInterviewId())
                .questionId(request.getQuestionId())
                .userId(user.getId())
                .responseTime(request.getResponseTime())
                .content(request.getContent())
                .createdAt(LocalDateTime.now())
                .build();
        answerRepository.save(answer);

        return AnswerResponse.from(answer);
    }

    public AnswerResponse getAnswerById(Long answerId) {
        AnswerEntity answer = answerRepository.findById(answerId)
                .orElseThrow(() -> BusinessException.notFound("답변을 찾을 수 없습니다"));
        return AnswerResponse.from(answer);
    }

    public List<AnswerResponse> getAllAnswer(Long interviewId) {
        List<AnswerEntity> answers = answerRepository.findAllByInterviewId(interviewId);
        return answers.stream()
                .map(AnswerResponse::from)
                .toList();
    }
}
