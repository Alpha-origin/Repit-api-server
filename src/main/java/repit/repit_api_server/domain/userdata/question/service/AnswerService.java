package repit.repit_api_server.domain.userdata.question.service;

import lombok.RequiredArgsConstructor;
import org.apache.tomcat.util.net.openssl.ciphers.Authentication;
import org.springframework.stereotype.Service;
import repit.repit_api_server.domain.userdata.question.dto.request.AnswerRequest;
import repit.repit_api_server.domain.userdata.question.dto.response.AnswerResponse;
import repit.repit_api_server.domain.userdata.question.repository.QuestionRepository;

@Service
@RequiredArgsConstructor
public class AnswerService {
    private final QuestionRepository questionRepository;

    public AnswerResponse createAnswer(Authentication authentication, AnswerRequest request) {
        
    }
}
