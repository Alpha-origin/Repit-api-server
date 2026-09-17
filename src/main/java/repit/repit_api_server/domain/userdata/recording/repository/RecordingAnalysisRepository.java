package repit.repit_api_server.domain.userdata.recording.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import repit.repit_api_server.domain.userdata.recording.entity.RecordingAnalysisEntity;
import repit.repit_api_server.domain.userdata.recording.entity.enums.RecordingAnalysisStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface RecordingAnalysisRepository extends JpaRepository<RecordingAnalysisEntity, Long> {

    Optional<RecordingAnalysisEntity> findByInterviewId(Long interviewId);

    List<RecordingAnalysisEntity> findAllByStatusAndLastActivityAtBefore(RecordingAnalysisStatus status,
                                                                        LocalDateTime before);

    /**
     * 기다리는 중인 건에만 활동 시각을 새로 찍는다. 이미 보냈거나 보내는 중이면 건드리지 않는다.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RecordingAnalysisEntity r set r.lastActivityAt = :now "
            + "where r.interviewId = :interviewId and r.status = repit.repit_api_server.domain.userdata.recording.entity.enums.RecordingAnalysisStatus.WAITING")
    int touchIfWaiting(Long interviewId, LocalDateTime now);

    /**
     * WAITING -> SENDING. 바뀐 행이 있을 때만 차지한 것이다.
     *
     * <p>영상 업로드, 기록 저장, 주기 스윕이 같은 면접을 동시에 보낼 수 있다. 읽고 나서 상태를
     * 바꾸면 둘 다 WAITING을 보고 분석 서버에 두 번 보낸다. 조건부 갱신 한 문장으로 한 곳만 통과시킨다.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RecordingAnalysisEntity r set r.status = repit.repit_api_server.domain.userdata.recording.entity.enums.RecordingAnalysisStatus.SENDING "
            + "where r.analysisId = :analysisId and r.status = repit.repit_api_server.domain.userdata.recording.entity.enums.RecordingAnalysisStatus.WAITING")
    int claim(Long analysisId);
}
