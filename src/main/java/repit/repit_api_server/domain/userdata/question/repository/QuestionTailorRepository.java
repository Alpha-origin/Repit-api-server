package repit.repit_api_server.domain.userdata.question.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import repit.repit_api_server.domain.userdata.interview.entity.enums.InterviewMode;
import repit.repit_api_server.domain.userdata.question.entity.QuestionTailorEntity;
import repit.repit_api_server.domain.userdata.question.entity.enums.TailorStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface QuestionTailorRepository extends JpaRepository<QuestionTailorEntity, Long> {

    Optional<QuestionTailorEntity> findByJobId(String jobId);

    Optional<QuestionTailorEntity> findTopByInterviewIdOrderByCreatedAtDesc(Long interviewId);

    /**
     * 어느 분석에서 비롯된 재작성인지로 찾는다.
     *
     * <p>웹은 분석 jobId 하나로 구독한다. 뒤늦게 붙은 구독에 면접 준비 완료를 되짚어주려면
     * 그 jobId에서 이어진 재작성을 찾아야 한다.
     */
    Optional<QuestionTailorEntity> findTopByAnalysisJobIdOrderByCreatedAtDesc(String analysisJobId);

    /**
     * 채팅 서버로 넘길 권리를 차지한다. 먼저 차지한 쪽만 1을 돌려받는다.
     *
     * <p>전달은 트랜잭션 밖에서 도는 외부 호출이라 수백 ms가 걸린다. 그동안 읽어둔 값만 보고
     * 판단하면, 콜백이 넘기는 중에 들어온 조회가 아직 넘기지 않은 것으로 보고 한 번 더 넘긴다.
     * 채팅 서버에는 같은 면접을 여는 요청이 두 번 도착한다.
     *
     * <p>읽고 쓰는 사이를 열어두지 않으려고 조건을 담은 갱신 한 번으로 끝낸다. 전달에 실패하면
     * 부르는 쪽이 이 표시를 되돌려 다음 기회에 다시 시도할 수 있게 한다.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update QuestionTailorEntity t
               set t.chatDelivered = true
             where t.tailorId = :tailorId
               and (t.chatDelivered is null or t.chatDelivered = false)
            """)
    int claimChatDelivery(@Param("tailorId") Long tailorId);

    /** 이 사용자·모드의 새 질문 사이클을 기다리는 준비. 사이클 콜백이 이어간다. */
    List<QuestionTailorEntity> findAllByUserIdAndModeAndStatus(Long userId, InterviewMode mode, TailorStatus status);

    /** 콜백을 기다리다 시간이 지난 건. 스케줄러가 이것들을 걷어낸다. */
    List<QuestionTailorEntity> findAllByStatusAndCreatedAtBefore(TailorStatus status, LocalDateTime createdAt);

    /**
     * 시간이 지난 건을 실패로 닫을 권리를 차지한다. 먼저 차지한 쪽만 1을 돌려받는다.
     *
     * <p>스케줄러 스윕과 준비 상태 조회가 같은 건을 동시에 집어들 수 있고, 서버가 여러 대면
     * 스윕끼리도 겹친다. 읽고 쓰는 사이를 열어두면 같은 실패를 여러 번 알리게 되므로 조건을
     * 담은 갱신 한 번으로 끝낸다. 차지한 쪽이 이어서 폴백 질문과 사유를 채워 저장한다.
     *
     * <p>새 질문 사이클을 기다리던 건도 같은 권리로 닫는다. 사이클 콜백과 시간 초과 정리가 겹칠 수 있다.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update QuestionTailorEntity t
               set t.status = repit.repit_api_server.domain.userdata.question.entity.enums.TailorStatus.FAILED
             where t.tailorId = :tailorId
               and t.status in (repit.repit_api_server.domain.userdata.question.entity.enums.TailorStatus.PENDING,
                                repit.repit_api_server.domain.userdata.question.entity.enums.TailorStatus.WAITING)
            """)
    int claimExpiration(@Param("tailorId") Long tailorId);

    /**
     * 사이클을 기다리던 건을 이어갈 권리를 차지한다. 먼저 차지한 쪽만 1을 돌려받는다.
     *
     * <p>시작 시각도 지금으로 옮긴다. 재작성 제한 시간은 이 시각부터 세므로, 옮기지 않으면 기다린 시간만큼
     * 재작성 요청을 보내자마자 시간 초과로 걷힌다. 생성 시각은 엔티티로는 고칠 수 없어 여기서만 바꾼다.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update QuestionTailorEntity t
               set t.status = repit.repit_api_server.domain.userdata.question.entity.enums.TailorStatus.PENDING,
                   t.createdAt = :now
             where t.tailorId = :tailorId
               and t.status = repit.repit_api_server.domain.userdata.question.entity.enums.TailorStatus.WAITING
            """)
    int claimResume(@Param("tailorId") Long tailorId, @Param("now") LocalDateTime now);
}
