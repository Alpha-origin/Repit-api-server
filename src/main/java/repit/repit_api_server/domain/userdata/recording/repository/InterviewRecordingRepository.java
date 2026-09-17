package repit.repit_api_server.domain.userdata.recording.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import repit.repit_api_server.domain.userdata.recording.entity.InterviewRecordingEntity;

import java.util.List;

public interface InterviewRecordingRepository extends JpaRepository<InterviewRecordingEntity, Long> {

    // 올라온 순서가 곧 답변 순서다.
    List<InterviewRecordingEntity> findAllByInterviewIdOrderByRecordingIdAsc(Long interviewId);
}
