package repit.repit_api_server.domain.userdata.interview.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewPersonaEntity;

import java.util.List;

public interface InterviewPersonaRepository extends JpaRepository<InterviewPersonaEntity, Long> {

    List<InterviewPersonaEntity> findAllByInterviewIdOrderByPersonaOrderAsc(Long interviewId);
}
