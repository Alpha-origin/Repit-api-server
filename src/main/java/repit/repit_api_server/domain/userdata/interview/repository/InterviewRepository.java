package repit.repit_api_server.domain.userdata.interview.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import repit.repit_api_server.domain.userdata.interview.dto.response.InterviewResponse;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;

import java.util.List;

public interface InterviewRepository extends JpaRepository<InterviewEntity, Long> {
    List<InterviewResponse> findAllByUserId(Long UserId);
}
