package repit.repit_api_server.domain.userdata.question.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import repit.repit_api_server.domain.userdata.question.entity.Answer;

public interface AnswerRepository extends JpaRepository<Answer, Long> {
}
