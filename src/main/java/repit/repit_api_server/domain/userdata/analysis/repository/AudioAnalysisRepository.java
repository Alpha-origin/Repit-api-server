package repit.repit_api_server.domain.userdata.analysis.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import repit.repit_api_server.domain.userdata.analysis.entity.AudioAnalysisEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AudioAnalysisRepository extends JpaRepository<AudioAnalysisEntity, Long> {

    Optional<AudioAnalysisEntity> findByRequestId(String requestId);

    Optional<AudioAnalysisEntity> findByJobId(String jobId);

    List<AudioAnalysisEntity> findAllByInterviewIdOrderByAnalysisIdAsc(Long interviewId);

    /**
     * 결과도 실패도 아닌 채 오래된 요청이 남은 면접.
     *
     * <p>접수를 확인하지 못했거나(job_id 없음) 콜백이 오지 않은 요청이다. 어느 쪽이든 그대로 두면 그 면접의
     * 음성 분석은 영영 끝나지 않으므로, 다시 확인할 자리로 돌려놓아야 한다.
     */
    @Query("select distinct a.interviewId from AudioAnalysisEntity a "
            + "where a.status = repit.repit_api_server.domain.userdata.analysis.entity.enums.AudioAnalysisStatus.PENDING "
            + "and a.createdAt < :checkBefore")
    List<Long> findUnresolvedInterviewIds(LocalDateTime checkBefore);
}
