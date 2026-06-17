package repit.repit_api_server.domain.userdata.question.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.question.dto.response.QuestionResponse;
import repit.repit_api_server.domain.userdata.question.entity.QuestionEntity;
import repit.repit_api_server.domain.userdata.question.repository.QuestionRepository;
import repit.repit_api_server.global.client.AiServerClient;

import java.util.List;

@Service
@RequiredArgsConstructor
public class QuestionService {
    private final QuestionRepository questionRepository;
    private final AiServerClient aiServerClient;
    private final InterviewRepository interviewRepository;

    public QuestionResponse createQuestion(String authorization) {
        QuestionResponse response = aiServerClient.createQuestion(authorization);
        QuestionEntity question = QuestionEntity.builder()
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
        QuestionEntity question = questionRepository.findById(questionId).orElse(null);
        if (question == null) {
            return null;
        }

        return QuestionResponse.from(question);
    }

    public List<QuestionResponse> getAllByInterview(String authorization, Long interviewId) {
        InterviewEntity interview = interviewRepository.findById(interviewId).orElse(null);
        List<QuestionEntity> questions = questionRepository.findAllByInterview(interview);
        return questions.stream()
                .map(QuestionResponse::from)
                .toList();
    }
}
