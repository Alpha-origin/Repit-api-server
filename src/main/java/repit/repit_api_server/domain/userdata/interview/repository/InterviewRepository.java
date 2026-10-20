package repit.repit_api_server.domain.userdata.interview.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.question.entity.QuestionTailorEntity;

import java.util.List;
import java.util.Optional;

public interface InterviewRepository extends JpaRepository<InterviewEntity, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from InterviewEntity i where i.interviewId = :interviewId")
    Optional<InterviewEntity> findForJobSnapshot(@Param("interviewId") Long interviewId);

    List<InterviewEntity> findAllByUserId(Long userId);

    Optional<InterviewEntity> findBySessionId(String sessionId);

    /**
     * 면접 기록 저장을 차지한다. 진행 중인 면접만 종료 상태로 바꾸고 1을 돌려받는다.
     *
     * <p>면접을 종료 상태로 바꾸는 곳은 기록 저장뿐이고 같은 트랜잭션에서 질문·답변을 넣는다. 그래서 종료 상태면 기록이
     * 이미 저장된 것이다. 채팅 서버가 같은 기록을 다시 보내면 0을 받아 아무것도 바꾸지 않는다.
     *
     * <p>먼저 읽어보고 판단하면 동시에 온 두 요청이 모두 처음이라고 보고 질문을 두 번 넣는다. 조건을 담은 갱신 한 번으로
     * 판단해, 뒤에 온 요청은 앞 요청이 끝날 때까지 행 잠금에서 기다렸다가 바뀐 상태를 보고 0을 받는다. 앞 요청이
     * 롤백되면 뒤 요청이 차지한다.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update InterviewEntity i
               set i.status = :status
             where i.interviewId = :interviewId
               and i.status = repit.repit_api_server.domain.userdata.interview.entity.enums.Status.IN_PROGRESS
            """)
    int claimResultSave(@Param("interviewId") Long interviewId, @Param("status") Status status);

    /**
     * 채점 요청을 보내기 직전에, 실어 보낼 답변 있는 문항 id를 남긴다. 콜백이 접수 응답보다 먼저 와 작업 행에 목록이 없을 때
     * 기업 관점 코멘트의 근거를 이것으로 가린다.
     *
     * <p>엔티티에 매핑하지 않고 이 쿼리로만 쓰고 읽는다. 매핑하면 같은 면접을 다른 곳에서 엔티티째 저장할 때 읽어 둔 옛 값으로
     * 덮어쓴다.
     */
    @Transactional
    @Modifying
    @Query(value = """
            update interview
               set last_feedback_question_ids = cast(:questionIds as jsonb)
             where interview_id = :interviewId
            """, nativeQuery = true)
    int recordLastFeedbackQuestionIds(@Param("interviewId") Long interviewId,
                                      @Param("questionIds") String questionIdsJson);

    // 위에서 남긴 목록의 JSON 원문. 남긴 적이 없으면 비어 있다.
    @Query(value = "select cast(last_feedback_question_ids as text) from interview where interview_id = :interviewId",
            nativeQuery = true)
    Optional<String> findLastFeedbackQuestionIds(@Param("interviewId") Long interviewId);
}
