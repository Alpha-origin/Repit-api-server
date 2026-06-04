package repit.repit_api_server.domain.userdata.question.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import repit.repit_api_server.domain.userdata.question.dto.request.QuestionRequest;
import repit.repit_api_server.domain.userdata.question.dto.response.QuestionResponse;
import repit.repit_api_server.domain.userdata.question.entity.Question;
import repit.repit_api_server.domain.userdata.question.repository.QuestionRepository;
import repit.repit_api_server.global.client.AiServerClient;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class QuestionService {
    private final QuestionRepository questionRepository;
    private final AiServerClient aiServerClient;

    public QuestionResponse createQuestion(String authorization) {
        QuestionResponse response = aiServerClient.createQuestion(authorization);
        Question question = Question.builder()
                .interview(response.getInterview())
                .question(response.getQuestion())
                .type(response.getType())
                .intention(response.getIntention())
                .content(response.getContent())
                .build();
        questionRepository.save(question);
        return response;
    }

    public QuestionResponse getQuestionById(String authorization, Long questionId) {
        Optional<Question> question = questionRepository.findById(questionId);
        return QuestionResponse.builder()
                .id(questionId)
                .interview(question.get().getInterview())
                .question(question.get().getQuestion())
                .type(question.get().getType())
                .intention(question.get().getIntention())
                .content(question.get().getContent())
                .build();
    }
}
