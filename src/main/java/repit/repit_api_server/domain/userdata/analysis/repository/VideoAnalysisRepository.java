package repit.repit_api_server.domain.userdata.analysis.repository;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import repit.repit_api_server.domain.userdata.analysis.entity.VideoAnalysisEntity;
import repit.repit_api_server.domain.userdata.recording.entity.InterviewRecordingEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 스윕이 바꾸는 상태는 조건부 갱신으로만 바꾼다.
 *
 * <p>스윕이 작업을 조회하는 동안 콜백이 먼저 결과를 저장할 수 있다. 읽어 둔 엔티티로 "실패"를 저장하면 방금 들어온
 * 결과를 덮으므로, 결과를 기다리는 중(PENDING)인지를 같은 문장에서 확인한다. 결과는 콜백이 쓰고, 그 결과가 이긴다.
 */
public interface VideoAnalysisRepository extends JpaRepository<VideoAnalysisEntity, Long> {

    Optional<VideoAnalysisEntity> findByRequestId(String requestId);

    Optional<VideoAnalysisEntity> findByJobId(String jobId);

    List<VideoAnalysisEntity> findAllByRecordingIdOrderByAnalysisIdAsc(Long recordingId);

    /**
     * 처음 요청할 영상. 면접의 최종 영상(가장 나중에 기록된 FULL_INTERVIEW)이고, 올라온 뒤 조용하며, 아직 요청 기록이
     * 없는 것.
     *
     * <p>원본 보관 기간이 거의 끝난 영상은 보내지 않는다. 분석 서버가 내려받기 전에 지워진다.
     */
    @Query("select r from InterviewRecordingEntity r "
            + "where r.kind = repit.repit_api_server.domain.userdata.recording.entity.enums.RecordingKind.FULL_INTERVIEW "
            + "and r.createdAt < :quietBefore and r.createdAt > :uploadedAfter "
            + "and r.recordingId = (select max(r2.recordingId) from InterviewRecordingEntity r2 "
            + "    where r2.interviewId = r.interviewId "
            + "    and r2.kind = repit.repit_api_server.domain.userdata.recording.entity.enums.RecordingKind.FULL_INTERVIEW) "
            + "and not exists (select 1 from VideoAnalysisEntity v where v.recordingId = r.recordingId) "
            + "order by r.recordingId")
    List<InterviewRecordingEntity> findVideosToStart(LocalDateTime quietBefore, LocalDateTime uploadedAfter, Limit limit);

    /** 결과를 기다리는 요청 가운데 다시 보내거나 작업을 조회할 때가 된 것. */
    @Query("select v from VideoAnalysisEntity v "
            + "where v.status = repit.repit_api_server.domain.userdata.analysis.entity.enums.VideoAnalysisStatus.PENDING "
            + "and v.nextCheckAt <= :now order by v.nextCheckAt")
    List<VideoAnalysisEntity> findDueChecks(LocalDateTime now, Limit limit);

    /**
     * 자동으로 다시 분석할 요청.
     *
     * <ul>
     *   <li>분석 서버가 재시도할 만하다고 알린 실패로 끝났다(partial은 넣지 않는다).</li>
     *   <li>그 영상의 가장 최근 요청이다. 이미 다음 요청이 있거나 사용자가 새 묶음을 열었으면 잇지 않는다.</li>
     *   <li>그 면접의 최종 영상이다. 새 영상이 올라왔으면 그 영상이 따로 요청된다.</li>
     *   <li>묶음의 작업 수가 한도 아래다. 새 요청 id마다 한도를 새로 세지 않는다.</li>
     * </ul>
     */
    @Query("select v from VideoAnalysisEntity v "
            + "where v.status in (repit.repit_api_server.domain.userdata.analysis.entity.enums.VideoAnalysisStatus.FAILED, "
            + "    repit.repit_api_server.domain.userdata.analysis.entity.enums.VideoAnalysisStatus.UNAVAILABLE) "
            + "and v.errorRetryable = true and v.updatedAt < :failedBefore "
            + "and v.analysisId = (select max(v2.analysisId) from VideoAnalysisEntity v2 where v2.recordingId = v.recordingId) "
            + "and v.recordingId = (select max(r.recordingId) from InterviewRecordingEntity r "
            + "    where r.interviewId = v.interviewId "
            + "    and r.kind = repit.repit_api_server.domain.userdata.recording.entity.enums.RecordingKind.FULL_INTERVIEW) "
            + "and (select count(v3) from VideoAnalysisEntity v3 where v3.chainId = v.chainId) < :maxJobsPerChain "
            + "order by v.analysisId")
    List<VideoAnalysisEntity> findReanalysisCandidates(LocalDateTime failedBefore, long maxJobsPerChain, Limit limit);

    /** 보내기 직전에 센다. 보내다 프로세스가 죽어도 한 번 보낸 것으로 남아 한도를 넘지 않는다. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update VideoAnalysisEntity v set v.sendCount = v.sendCount + 1, v.updatedAt = :now "
            + "where v.analysisId = :analysisId")
    int countSend(Long analysisId, LocalDateTime now);

    /** 접수 응답의 작업 id를 적는다. 콜백이 먼저 와서 이미 끝난 요청이면 건드리지 않는다. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update VideoAnalysisEntity v set v.jobId = :jobId, v.nextCheckAt = :nextCheckAt, v.updatedAt = :now "
            + "where v.analysisId = :analysisId "
            + "and v.status = repit.repit_api_server.domain.userdata.analysis.entity.enums.VideoAnalysisStatus.PENDING")
    int acknowledge(Long analysisId, String jobId, LocalDateTime nextCheckAt, LocalDateTime now);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update VideoAnalysisEntity v set v.nextCheckAt = :nextCheckAt, v.errorMessage = :note, v.updatedAt = :now "
            + "where v.analysisId = :analysisId "
            + "and v.status = repit.repit_api_server.domain.userdata.analysis.entity.enums.VideoAnalysisStatus.PENDING")
    int postpone(Long analysisId, LocalDateTime nextCheckAt, String note, LocalDateTime now);

    /** 결과 없이 닫는다. 그 사이 결과가 들어왔으면 결과를 남긴다. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update VideoAnalysisEntity v "
            + "set v.status = repit.repit_api_server.domain.userdata.analysis.entity.enums.VideoAnalysisStatus.FAILED, "
            + "v.errorCode = :code, v.errorRetryable = :retryable, v.errorMessage = :message, v.nextCheckAt = null, "
            + "v.updatedAt = :now "
            + "where v.analysisId = :analysisId "
            + "and v.status = repit.repit_api_server.domain.userdata.analysis.entity.enums.VideoAnalysisStatus.PENDING")
    int fail(Long analysisId, String code, Boolean retryable, String message, LocalDateTime now);
}
