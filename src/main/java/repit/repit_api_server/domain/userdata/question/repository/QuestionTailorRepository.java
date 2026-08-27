package repit.repit_api_server.domain.userdata.question.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import repit.repit_api_server.domain.userdata.question.entity.QuestionTailorEntity;

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
}
