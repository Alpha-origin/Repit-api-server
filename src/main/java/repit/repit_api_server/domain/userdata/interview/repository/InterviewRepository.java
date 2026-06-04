package repit.repit_api_server.domain.userdata.interview.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import repit.repit_api_server.domain.userdata.interview.entity.Interview;
import repit.repit_api_server.domain.userdata.question.entity.Question;

import java.lang.ScopedValue;
import java.util.List;

public interface InterviewRepository extends JpaRepository<Interview, Long> {
    Interview findByQuestion(Question question);

    List<Interview> findAllByUserId(Long UserId);
}
