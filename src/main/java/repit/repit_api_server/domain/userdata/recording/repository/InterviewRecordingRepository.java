package repit.repit_api_server.domain.userdata.recording.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import repit.repit_api_server.domain.userdata.recording.entity.InterviewRecordingEntity;
import repit.repit_api_server.domain.userdata.recording.entity.enums.RecordingKind;

import java.util.List;
import java.util.Optional;

public interface InterviewRecordingRepository extends JpaRepository<InterviewRecordingEntity, Long> {

    // 올라온 순서가 곧 답변 순서다.
    List<InterviewRecordingEntity> findAllByInterviewIdAndKindOrderByRecordingIdAsc(Long interviewId, RecordingKind kind);

    /** 면접 화면 전체 녹화. 같은 면접에 두 번 올라오면 나중 것이 그 면접의 영상이다. */
    Optional<InterviewRecordingEntity> findFirstByInterviewIdAndKindOrderByRecordingIdDesc(Long interviewId, RecordingKind kind);

    boolean existsByInterviewIdAndKind(Long interviewId, RecordingKind kind);
}
