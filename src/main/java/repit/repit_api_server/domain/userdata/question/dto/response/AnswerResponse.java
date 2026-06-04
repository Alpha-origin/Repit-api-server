package repit.repit_api_server.domain.userdata.question.dto.response;

import repit.repit_api_server.domain.userdata.interview.entity.Interview;
import repit.repit_api_server.domain.userdata.question.entity.Question;

import java.time.LocalDateTime;

public class AnswerResponse {
    private Long id;

    private Interview interview;

    private Question question;

    private Long userId;

    private int responseTime;

    private String content;

    private LocalDateTime createdAt;
}
