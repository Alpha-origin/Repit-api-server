package repit.repit_api_server.domain.userdata.interview.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import repit.repit_api_server.domain.userdata.interview.dto.response.InterviewResponse;
import repit.repit_api_server.domain.userdata.interview.entity.Interview;
import repit.repit_api_server.domain.userdata.question.entity.Question;

import java.util.List;

public interface InterviewRepository extends JpaRepository<Interview, Long> {
    Interview findByQuestion(Question question);

    List<InterviewResponse> findAllByUserId(Long UserId);
}
