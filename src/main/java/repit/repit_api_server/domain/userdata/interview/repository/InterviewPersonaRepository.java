package repit.repit_api_server.domain.userdata.interview.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewPersonaEntity;

import java.util.List;

public interface InterviewPersonaRepository extends JpaRepository<InterviewPersonaEntity, Long> {

    List<InterviewPersonaEntity> findAllByInterviewIdOrderByPersonaOrderAsc(Long interviewId);

    /** 여러 면접의 면접관을 한 번에. 목록 조회가 면접 수만큼 쿼리를 늘리지 않도록 한다. */
    List<InterviewPersonaEntity> findAllByInterviewIdInOrderByInterviewIdAscPersonaOrderAsc(List<Long> interviewIds);
}
