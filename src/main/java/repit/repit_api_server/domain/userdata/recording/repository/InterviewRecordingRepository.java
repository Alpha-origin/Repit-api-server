package repit.repit_api_server.domain.userdata.recording.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import repit.repit_api_server.domain.userdata.recording.entity.InterviewRecordingEntity;

public interface InterviewRecordingRepository extends JpaRepository<InterviewRecordingEntity, Long> {
}
