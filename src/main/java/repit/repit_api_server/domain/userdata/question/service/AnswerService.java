package repit.repit_api_server.domain.userdata.question.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.question.dto.request.AnswerRequest;
import repit.repit_api_server.domain.userdata.question.dto.response.AnswerResponse;
import repit.repit_api_server.domain.userdata.question.entity.AnswerEntity;
import repit.repit_api_server.domain.userdata.question.entity.QuestionEntity;
import repit.repit_api_server.domain.userdata.question.repository.AnswerRepository;
import repit.repit_api_server.domain.userdata.question.repository.QuestionRepository;
import repit.repit_api_server.global.client.AuthServerClient;
import repit.repit_api_server.global.response.UserResponse;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AnswerService {
    private final QuestionRepository questionRepository;
    private final AnswerRepository answerRepository;
    private final AuthServerClient authServerClient;
    private final InterviewRepository interviewRepository;

    public AnswerResponse createAnswer(String authorization, AnswerRequest request) {
        UserResponse user = authServerClient.getUser(authorization);
        QuestionEntity question = questionRepository.findById(request.getQuestion().getQuestionId()).orElse(null);
        if (question == null) {
            return null;
        }
        AnswerEntity answer = AnswerEntity.builder()
                .interviewId(request.getInterview())
                .questionId(request.getQuestion())
                .userId(user.getId())
                .responseTime(request.getResponseTime())
                .content(request.getContent())
                .build();
        answerRepository.save(answer);

        return AnswerResponse.from(answer);
    }

    public AnswerResponse getAnswerById(String authorization, Long answerId) {
        AnswerEntity answer = answerRepository.findById(answerId).orElse(null);
        if (answer == null) {
            return null;
        }
        return AnswerResponse.from(answer);
    }

    public List<AnswerResponse> getAllAnswer(String authorization, Long interviewId) {
        InterviewEntity interview = interviewRepository.findById(interviewId).orElse(null);
        List<AnswerEntity> answers = answerRepository.findAllByInterviewId(interview);
        return answers.stream()
                .map(AnswerResponse::from)
                .toList();
    }
}
