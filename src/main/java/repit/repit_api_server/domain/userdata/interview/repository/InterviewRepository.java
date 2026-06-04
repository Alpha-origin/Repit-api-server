package repit.repit_api_server.domain.userdata.interview.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import repit.repit_api_server.domain.userdata.interview.dto.response.InterviewResponse;
import repit.repit_api_server.domain.userdata.interview.entity.Interview;

import java.util.List;

public interface InterviewRepository extends JpaRepository<Interview, Long> {
    List<InterviewResponse> findAllByUserId(Long UserId);

    Interview findByUserId(Long userId);
}
