package repit.repit_api_server.domain.userdata.answer.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import repit.repit_api_server.domain.userdata.answer.entity.AnswerEntity;

import java.util.List;

public interface AnswerRepository extends JpaRepository<AnswerEntity, Long> {
    List<AnswerEntity> findAllByInterviewId(Long interviewId);
}
