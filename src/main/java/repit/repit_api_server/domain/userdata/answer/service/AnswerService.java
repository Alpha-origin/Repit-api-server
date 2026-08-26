package repit.repit_api_server.domain.userdata.answer.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.CrossOrigin;
import repit.repit_api_server.domain.userdata.answer.dto.request.AnswerRequest;
import repit.repit_api_server.domain.userdata.answer.dto.response.AnswerResponse;
import repit.repit_api_server.domain.userdata.answer.entity.AnswerEntity;
import repit.repit_api_server.domain.userdata.question.entity.QuestionEntity;
import repit.repit_api_server.domain.userdata.answer.repository.AnswerRepository;
import repit.repit_api_server.domain.userdata.question.repository.QuestionRepository;
import repit.repit_api_server.global.client.AuthServerClient;
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
        QuestionEntity question = questionRepository.findById(request.getQuestionId()).orElse(null);
        if (question == null) {
            return null;
        }
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
        AnswerEntity answer = answerRepository.findById(answerId).orElse(null);
        if (answer == null) {
            return null;
        }
        return AnswerResponse.from(answer);
    }

    public List<AnswerResponse> getAllAnswer(Long interviewId) {
        List<AnswerEntity> answers = answerRepository.findAllByInterviewId(interviewId);
        return answers.stream()
                .map(AnswerResponse::from)
                .toList();
    }
}
