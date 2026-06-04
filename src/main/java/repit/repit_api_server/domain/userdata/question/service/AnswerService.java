package repit.repit_api_server.domain.userdata.question.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import repit.repit_api_server.domain.userdata.interview.entity.Interview;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.question.dto.request.AnswerRequest;
import repit.repit_api_server.domain.userdata.question.dto.response.AnswerResponse;
import repit.repit_api_server.domain.userdata.question.entity.Answer;
import repit.repit_api_server.domain.userdata.question.entity.Question;
import repit.repit_api_server.domain.userdata.question.repository.AnswerRepository;
import repit.repit_api_server.domain.userdata.question.repository.QuestionRepository;
import repit.repit_api_server.global.client.AuthServerClient;
import repit.repit_api_server.global.response.UserResponse;

@Service
@RequiredArgsConstructor
public class AnswerService {
    private final QuestionRepository questionRepository;
    private final InterviewRepository interviewRepository;
    private final AnswerRepository answerRepository;
    private final AuthServerClient authServerClient;

    public AnswerResponse createAnswer(String authorization, AnswerRequest request) {
        UserResponse user = authServerClient.getUser(authorization);
        Question question = questionRepository.findById(request.getQuestion().getId()).orElse(null);
        if (question == null) {
            return null;
        }
        Interview interview = interviewRepository.findByQuestion(question);
        if (interview == null) {
            return null;
        }
        Answer answer = Answer.builder()
                .interview(interview)
                .question(request.getQuestion())
                .userId(user.getId())
                .responseTime(request.getResponseTime())
                .content(request.getContent())
                .build();
        answerRepository.save(answer);

        return AnswerResponse.from(answer);
    }
}
